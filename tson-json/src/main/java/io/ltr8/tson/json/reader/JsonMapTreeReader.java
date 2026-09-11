package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.MapBody;

import java.math.BigInteger;
import java.util.Optional;

/**
 * What both map forms share: [TSON-JSON] §6.5's entry-value rule and its size facets, and the test that picks
 * between the two subclasses.
 *
 * <p><b>The form is selected by the schema -- by {@code K}, never by inspecting the value -- and it is
 * therefore selected once, by the factory, which returns the reader for it.</b> That is §4.1's rule taken
 * literally: an object is one syntax for a record and a map both, so a decoder that decided per value would be
 * reading speculatively. It also means neither subclass carries the other's state or a branch it never takes.
 *
 * <ul>
 *   <li>{@link JsonObjectMapReader} -- when {@code K} resolves to a type whose parsing contract reads token
 *       content: every atom family, every enum, and {@code identifier}. A JSON object, each member name a key
 *       token faced to {@code K}'s own contract.</li>
 *   <li>{@link JsonPairsMapReader} -- when {@code K} is anything else, the compound keys [TSON-DATA] §2.6
 *       admits. A JSON array of two-element arrays, and the second of §8.3's class-stability leaks.</li>
 * </ul>
 *
 * <p><b>Nothing is reserved at a map position</b> (§3.2): keys are data, not names, so {@code "$schema"} under
 * {@code {text => text}} is an ordinary key. §8.3.1's carve-out is for a map standing as a choice variant and
 * belongs to the choice reader.
 */
abstract sealed class JsonMapTreeReader implements JsonTypeReader<JsonValue>
        permits JsonObjectMapReader, JsonPairsMapReader {

    static final JsonValueReaderFactory FACTORY = (name, definition, context) -> {
        MapBody body = (MapBody) definition.body();
        JsonSchemaLocation at = context.locationOf(name, definition);
        JsonTypeReader<?> value = context.readers().resolve(body.valueType().name());
        Optional<AtomType<?>> key = scalarKeyParser(context.schema(), body.keyType().name());
        return key.<JsonMapTreeReader>map(parser -> new JsonObjectMapReader(name, body, parser, value, at))
                .orElseGet(() -> new JsonPairsMapReader(name, body,
                        context.readers().resolve(body.keyType().name()), value, at));
    };

    final String name;
    final MapBody body;
    private final JsonTypeReader<?> value;
    private final JsonSchemaLocation schemaLocation;

    JsonMapTreeReader(String name, MapBody body, JsonTypeReader<?> value, JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.body = body;
        this.value = value;
        this.schemaLocation = schemaLocation;
    }

    /** Whether {@code body} takes the object form -- §8.3 asks the same question to judge class stability. */
    static boolean isObjectForm(TsonSchema schema, MapBody body) {
        return scalarKeyParser(schema, body.keyType().name()).isPresent();
    }

    /**
     * {@code K}'s own parser when {@code K} is a type a single scalar token denotes -- §6.5's test for the
     * object form, and the same line [TSON-SCHEMA] §5.2 draws for value modifiers. Empty for every other
     * {@code K}, which takes the pairs form.
     *
     * <p>The kernel's {@code value} and {@code void} fall to pairs by declining a parser rather than by being
     * listed: neither has a content grammar for a key token to face, and §6.5 states the test over the
     * contract for exactly that reason.
     */
    private static Optional<AtomType<?>> scalarKeyParser(TsonSchema schema, String keyTypeName) {
        JsonTypes.Resolved terminal = JsonTypes.terminal(schema, keyTypeName).orElseThrow(() ->
                new IllegalStateException("'" + keyTypeName + "' does not resolve -- linking should have refused it"));
        return terminal.definition().body() instanceof Atom atom
                ? AtomParsers.forType(terminal.name(), atom)
                : Optional.empty();
    }

    @Override
    public final JsonValue read(JsonReadContext ctx) {
        return readEntries(ctx.underDeclaration(schemaLocation));
    }

    /** The form's own walk, the cursor at the value's opening event. */
    abstract JsonValue readEntries(JsonReadContext ctx);

    /**
     * An entry's value. {@code {K => V?}} admits JSON null as the entry's <b>absent value</b> -- the entry is
     * present, counts toward the size bounds, and carries no value; under {@code {K => V}} a null entry value
     * is a validation error, as with an array element (§7).
     */
    final JsonValue entryValue(JsonReadContext at, String keySegment) {
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
    final void checkSize(JsonReadContext ctx, int count) {
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

    /** The value was not the shape this form takes -- the schema chose the form, so the document is wrong. */
    final JsonValue wrongShape(JsonReadContext ctx, JsonEvent found, String expected) {
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'%s' is a map, which in this schema takes %s, and this is %s"
                .formatted(name, expected, JsonAtoms.describe(found)), expected, JsonAtoms.describe(found));
        JsonEventSkip.value(ctx, found);
        return JsonNull.INSTANCE;
    }
}
