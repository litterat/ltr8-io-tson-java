package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomRefusal;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A map in one of its two JSON forms: [TSON-JSON] §6.5.
 *
 * <p><b>The form is selected by the schema -- by {@code K}, never by inspecting the value</b> -- so it is
 * decided once, here, at compile time. That is §4.1's rule applied to the case that most needs it: an object
 * is one syntax for a record and a map both, and a decoder that chose by looking would be reading
 * speculatively.
 *
 * <ul>
 *   <li><b>Object form</b>, when {@code K} resolves to an atom-family instance or an enum -- the types a
 *       single scalar token denotes directly. Each member name is a <em>key token</em> whose string content
 *       faces {@code K}'s own parsing contract exactly as §5.1 hands value strings, so {@code
 *       {"2026-07-01": 12.5}} under {@code {date => number}} carries a date key.</li>
 *   <li><b>Pairs form</b>, when {@code K} is anything else -- the compound keys [TSON-DATA] §2.6 admits. A
 *       JSON array of two-element arrays. This is the second of §8.3's class-stability leaks: a brace-class
 *       type wearing bracket clothing.</li>
 * </ul>
 *
 * <p><b>Keys compare as values, not as spellings.</b> Identity under a declared key type is over its value
 * space ([TSON-SCHEMA] §5.5), so {@code "1"} and {@code "1.0"} under a {@code number} key are one key and two
 * spellings of one octet string under a {@code bytes} key are one key. {@link JsonValueIdentity} is what
 * answers that, and it is the same answer the TSON reader gives -- one question, one verdict, both encodings.
 *
 * <p><b>Nothing is reserved at a map position</b> (§3.2): keys are data, not names, so {@code "$schema"} under
 * {@code {text => text}} is an ordinary key. §8.3.1's carve-out is for a map standing as a choice variant and
 * belongs to §8.
 */
final class JsonMapTreeReader implements JsonTypeReader<JsonValue> {

    static final JsonValueReaderFactory FACTORY = (name, definition, context) -> {
        MapBody body = (MapBody) definition.body();
        Optional<AtomType<?>> key = scalarKeyParser(context.schema(), body.keyType().name());
        return new JsonMapTreeReader(name, body, key.orElse(null),
                key.isPresent() ? null : context.readers().resolve(body.keyType().name()),
                context.readers().resolve(body.valueType().name()), context.locationOf(name, definition));
    };

    /** How many reference hops before this gives up; linking has already refused a cycle, so this is a guard. */
    private static final int MAX_REFERENCE_HOPS = 64;

    /**
     * {@code K}'s own parser when {@code K} is a type a single scalar token denotes -- which is exactly
     * §6.5's test for the object form, and the same line [TSON-SCHEMA] §5.2 draws for value modifiers.
     * Empty for every other {@code K}, which takes the pairs form.
     *
     * <p>The kernel's {@code value} and {@code void} fall to pairs form by declining a parser rather than by
     * being listed: neither has a content grammar for a key token to face, and neither is a key any schema
     * has reason to declare.
     */
    private static Optional<AtomType<?>> scalarKeyParser(TsonSchema schema, String keyTypeName) {
        String name = keyTypeName;
        Top body = null;
        for (int hops = 0; hops < MAX_REFERENCE_HOPS && body == null; hops++) {
            TypeDefinition definition = schema.entries().get(name);
            if (definition == null) {
                throw new IllegalStateException("'" + name + "' is not declared -- linking should have refused this");
            }
            if (definition.body() instanceof Reference reference) {
                name = reference.target().name();
            } else {
                body = definition.body();
            }
        }
        return body instanceof Atom atom ? AtomParsers.forType(name, atom) : Optional.empty();
    }

    private final String name;
    private final MapBody body;
    private final AtomType<?> keyParser;
    private final JsonTypeReader<?> compoundKey;
    private final JsonTypeReader<?> value;
    private final JsonSchemaLocation schemaLocation;

    private JsonMapTreeReader(String name, MapBody body, AtomType<?> keyParser, JsonTypeReader<?> compoundKey,
                              JsonTypeReader<?> value, JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.body = body;
        this.keyParser = keyParser;
        this.compoundKey = compoundKey;
        this.value = value;
        this.schemaLocation = schemaLocation;
    }

    @Override
    public JsonValue read(JsonReadContext ctx) {
        ctx = ctx.underDeclaration(schemaLocation);
        return keyParser != null ? readObjectForm(ctx) : readPairsForm(ctx);
    }

    // ── Object form ──────────────────────────────────────────────────────

    private JsonValue readObjectForm(JsonReadContext ctx) {
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ObjectStart)) {
            return wrongShape(ctx, first, "a JSON object");
        }
        Map<String, JsonValue> entries = new LinkedHashMap<>();
        Map<Object, String> byIdentity = new HashMap<>();
        // Counted separately from `entries`, which drops a member whose key the contract refused: the size
        // facets judge what the document stated, and a member nothing could file is still an entry it wrote.
        int count = 0;
        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                break;
            }
            if (!(event instanceof JsonEvent.MemberName member)) {
                throw new IllegalStateException("a member name or '}' was due and the stream produced " + event);
            }
            count++;
            JsonReadContext at = ctx.field(member.name());
            Object key = decodeKey(at, member.name());
            JsonValue entry = entryValue(at, member.name());
            if (key == null) {
                // A member name K's contract rejected: reported already, and there is no key to file the
                // entry under. Leaving it out of `byIdentity` also stops a second undecodable name being
                // reported a second time as a repeat of the first.
                continue;
            }
            String slot = byIdentity.putIfAbsent(JsonValueIdentity.of(key), member.name());
            if (slot != null) {
                at.report(Diagnostic.Code.DUPLICATE_MAP_KEY,
                        "duplicate key '%s' in '%s' -- a map states each key at most once, and the repeat states "
                                .formatted(member.name(), name) + "an entry for nothing",
                        "each key stated once", "'" + member.name() + "' stated again");
            }
            entries.put(slot != null ? slot : member.name(), entry);
        }
        checkSize(ctx, count);
        return new JsonObject(entries);
    }

    /** §6.5: the member name's string content faces {@code K}'s contract. Null where the contract refused it. */
    private Object decodeKey(JsonReadContext at, String memberName) {
        try {
            return keyParser.read(memberName);
        } catch (AtomTypeException e) {
            AtomRefusal refusal = AtomRefusal.of(e, memberName, Object.class).named(name + " key");
            at.report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
            return null;
        }
    }

    // ── Pairs form ───────────────────────────────────────────────────────

    private JsonValue readPairsForm(JsonReadContext ctx) {
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ArrayStart)) {
            return wrongShape(ctx, first, "a JSON array of two-element arrays");
        }
        List<JsonValue> entries = new ArrayList<>();
        Map<Object, Integer> byIdentity = new HashMap<>();
        int count = 0;
        while (!(ctx.peek() instanceof JsonEvent.ArrayEnd)) {
            JsonReadContext at = ctx.index(count);
            JsonValue pair = readPair(at, byIdentity, count++);
            if (pair != null) {
                entries.add(pair);
            }
        }
        ctx.next();   // ArrayEnd
        checkSize(ctx, count);
        return new JsonArray(entries);
    }

    /** One {@code [k, v]}. §6.5: an element that is not a two-element array is a validation error. */
    private JsonValue readPair(JsonReadContext at, Map<Object, Integer> byIdentity, int index) {
        JsonEvent opening = at.next();
        if (!(opening instanceof JsonEvent.ArrayStart)) {
            at.report(Diagnostic.Code.TYPE_MISMATCH,
                    "'%s' is in pairs form, whose every element is a two-element array, and this is %s"
                            .formatted(name, JsonAtoms.describe(opening)),
                    "a two-element array", JsonAtoms.describe(opening));
            JsonEventSkip.value(at, opening);
            return null;
        }
        if (at.peek() instanceof JsonEvent.ArrayEnd) {
            at.next();
            at.report(Diagnostic.Code.WRONG_ARITY, "'%s' is in pairs form and this entry has no key".formatted(name),
                    "a two-element array", "an empty array");
            return null;
        }
        int before = at.reported();
        JsonValue key = (JsonValue) compoundKey.read(at.index(0));
        if (at.peek() instanceof JsonEvent.ArrayEnd) {
            at.next();
            at.report(Diagnostic.Code.WRONG_ARITY, "'%s' is in pairs form and this entry has no value"
                    .formatted(name), "a two-element array", "a one-element array");
            return null;
        }
        JsonValue entry = entryValue(at.index(1), String.valueOf(index));
        if (at.reported() == before && byIdentity.putIfAbsent(JsonValueIdentity.of(key), index) != null) {
            at.report(Diagnostic.Code.DUPLICATE_MAP_KEY,
                    "duplicate key in '%s' -- a map states each key at most once, and the repeat states an entry "
                            .formatted(name) + "for nothing",
                    "each key stated once", String.valueOf(key));
        }
        if (!(at.peek() instanceof JsonEvent.ArrayEnd)) {
            // More than two elements. One diagnostic for the entry, then the rest discarded: there is no
            // position for any of them to be read at, and one malformed entry is one problem.
            at.report(Diagnostic.Code.WRONG_ARITY, "'%s' is in pairs form and this entry has more than a key and "
                    .formatted(name) + "a value", "a two-element array", "a longer array");
            while (!(at.peek() instanceof JsonEvent.ArrayEnd)) {
                JsonEventSkip.nextValue(at);
            }
        }
        at.next();   // the pair's own ArrayEnd
        return new JsonArray(List.of(key, entry));
    }

    // ── Shared ───────────────────────────────────────────────────────────

    /**
     * An entry's value. {@code {K => V?}} admits null as the entry's <b>absent value</b> -- the entry is
     * present, counts toward the size bounds, and carries no value; under {@code {K => V}} a null entry value
     * is a validation error, as with an array element (§7).
     */
    private JsonValue entryValue(JsonReadContext at, String keySegment) {
        if (at.peek() instanceof JsonEvent.NullValue) {
            at.next();
            if (body.state() == ElementState.REQUIRED) {
                at.report(Diagnostic.Code.FIELD_REQUIRED,
                        "'%s' entry '%s' is absent, but values are required".formatted(name, keySegment),
                        "a value", "(absent)");
            }
            return JsonNull.INSTANCE;
        }
        return (JsonValue) value.read(at);
    }

    /** §6.5: size facets count entries -- an entry with an absent value is an entry. */
    private void checkSize(JsonReadContext ctx, int count) {
        BigInteger size = BigInteger.valueOf(count);
        body.minItems().filter(min -> size.compareTo(min) < 0).ifPresent(min -> ctx.report(
                Diagnostic.Code.TYPE_MISMATCH,
                "'%s' takes at least %s entries, and this has %d".formatted(name, min, count),
                ">= " + min, String.valueOf(count)));
        body.maxItems().filter(max -> size.compareTo(max) > 0).ifPresent(max -> ctx.report(
                Diagnostic.Code.TYPE_MISMATCH,
                "'%s' takes at most %s entries, and this has %d".formatted(name, max, count),
                "<= " + max, String.valueOf(count)));
    }

    private JsonValue wrongShape(JsonReadContext ctx, JsonEvent found, String expected) {
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'%s' is a map, which in this schema takes %s, and this is %s"
                .formatted(name, expected, JsonAtoms.describe(found)), expected, JsonAtoms.describe(found));
        JsonEventSkip.value(ctx, found);
        return JsonNull.INSTANCE;
    }
}
