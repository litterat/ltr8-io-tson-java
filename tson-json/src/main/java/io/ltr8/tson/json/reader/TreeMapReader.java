package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.diagnostics.MapDiagnostics;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EntryDisplayName;
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
 *   <li>{@link TreeMapObjectReader} -- when {@code K} resolves to a type whose parsing contract reads token
 *       content: every atom family, every enum, and {@code identifier}. A JSON object, each member name a key
 *       token faced to {@code K}'s own contract.</li>
 *   <li>{@link TreeMapPairsReader} -- when {@code K} is anything else, the compound keys [TSON-DATA] §2.6
 *       admits. A JSON array of two-element arrays, and the second of §8.3's class-stability leaks.</li>
 * </ul>
 *
 * <p><b>Nothing is reserved at a map position</b> (§3.2): keys are data, not names, so {@code "$schema"} under
 * {@code {text => text}} is an ordinary key. §8.3.1's carve-out is for a map standing as a choice variant and
 * belongs to the choice reader.
 */
abstract sealed class TreeMapReader implements JsonTypeReader<JsonValue>
        permits TreeMapObjectReader, TreeMapPairsReader {

    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        MapBody body = (MapBody) definition.body();
        JsonSchemaLocation at = context.locationOf(name, definition);
        // The name the author wrote, not the entry the resolver minted: `{text => int32}` rather than a
        // content-derived `map_text_int32_...`, which appears in neither the schema nor the document.
        String shown = EntryDisplayName.of(name, definition, context.schema().entries());
        JsonTypeReader<?> value = context.readers().resolve(body.valueType().name());
        Optional<AtomType<?>> key = scalarKeyParser(context.schema(), body.keyType().name());
        return key.<TreeMapReader>map(parser -> new TreeMapObjectReader(shown, body, parser, value, at))
                .orElseGet(() -> new TreeMapPairsReader(shown, body,
                        context.readers().resolve(body.keyType().name()), value, at));
    };

    final String name;
    final MapBody body;

    /** This map's rules, shared with the TSON reader ([TSON-JSON] §9.4). */
    final MapDiagnostics rules;
    private final JsonTypeReader<?> value;
    private final JsonSchemaLocation schemaLocation;

    TreeMapReader(String name, MapBody body, JsonTypeReader<?> value, JsonSchemaLocation schemaLocation) {
        this.name = name;
        this.body = body;
        this.rules = new MapDiagnostics(name);
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
        ReferenceChain.Resolved terminal = ReferenceChain.terminal(schema, keyTypeName).orElseThrow(() ->
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
                at.report(rules.absentEntryValue(keySegment, ABSENT));
            }
            return JsonNull.INSTANCE;
        }
        return (JsonValue) value.read(at);
    }

    /** §6.5: size facets count entries -- an entry with an absent value is an entry. */
    final void checkSize(JsonReadContext ctx, int count) {
        BigInteger size = BigInteger.valueOf(count);
        body.minItems().filter(min -> size.compareTo(min) < 0)
                .ifPresent(min -> ctx.report(rules.tooFewEntries(min, count)));
        body.maxItems().filter(max -> size.compareTo(max) > 0)
                .ifPresent(max -> ctx.report(rules.tooManyEntries(max, count)));
    }

    /** The value was not the shape this form takes -- the schema chose the form, so the document is wrong. */
    final JsonValue wrongShape(JsonReadContext ctx, JsonEvent found) {
        ctx.report(rules.notAMap(JsonAtoms.describe(found)));
        EventSkip.value(ctx, found);
        return JsonNull.INSTANCE;
    }

    /** How a JSON document spells absence (§7), for the `actual` of a rule about an entry value's state. */
    static final String ABSENT = "null";
}
