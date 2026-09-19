package io.ltr8.tson.json.reader;

import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.base.diagnostics.MapDiagnostics;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.math.BigInteger;
import java.util.Optional;

/**
 * What a map's schema fixes, resolved once when the schema compiles: [TSON-JSON] §6.5's half that does not depend
 * on what the read builds -- which of the two forms the map takes, the entry value's state and reader, the size
 * facets, and the diagnostics. A mode's factory decides the readers and hands them to the form's loop ({@link
 * MapObjectReader}, {@link MapPairsReader}); bind mode keeps the plan to build the same position again for a
 * component's own map class ({@link BindTargets}).
 *
 * <p><b>The form is selected by the schema -- by {@code K}, never by inspecting the value -- and it is therefore
 * selected once, here.</b> That is §4.1's rule taken literally: an object is one syntax for a record and a map
 * both, so a decoder that decided per value would be reading speculatively.
 * <ul>
 *   <li><b>Object form</b> -- when {@code K} resolves to a type whose parsing contract reads token content: every
 *       atom family, every enum, and {@code identifier}. A JSON object, each member name a key token faced to
 *       {@code K}'s own contract ({@link #keyParser}).</li>
 *   <li><b>Pairs form</b> -- when {@code K} is anything else, the compound keys [TSON-DATA] §2.6 admits. A JSON
 *       array of two-element arrays, each key read at {@code K}'s reader ({@link #schemaKey}), and the second of
 *       §8.3's class-stability leaks.</li>
 * </ul>
 *
 * <p><b>Nothing is reserved at a map position</b> (§3.2): keys are data, not names, so {@code "$schema"} under
 * {@code {text => text}} is an ordinary key. §8.3.1's escape is for a map standing as a choice variant, and
 * belongs to the choice reader.
 */
record MapPlan(String displayName, JsonSchemaLocation schemaLocation, boolean optionalValues,
               Optional<BigInteger> minItems, Optional<BigInteger> maxItems, AtomType<?> keyParser,
               JsonTypeReader<?> schemaKey, JsonTypeReader<?> schemaValue, MapDiagnostics rules) {

    static MapPlan of(String name, TypeDefinition definition, ValueReaderContext context) {
        MapBody body = (MapBody) definition.body();
        // The name the author wrote, not the entry the resolver minted: `{text => int32}` rather than a
        // content-derived `map_text_int32_...`, which appears in neither the schema nor the document.
        String displayName = EntryDisplayName.of(name, definition, context.schema().entries());
        AtomType<?> keyParser = scalarKeyParser(context.schema(), body.keyType().name()).orElse(null);
        return new MapPlan(displayName, context.locationOf(name, definition),
                body.state() == ElementState.OPTIONAL, body.minItems(), body.maxItems(), keyParser,
                keyParser == null ? context.readers().resolve(body.keyType().name()) : null,
                context.readers().resolve(body.valueType().name()), new MapDiagnostics(displayName));
    }

    /** Whether this map takes the object form. */
    boolean objectForm() {
        return keyParser != null;
    }

    /** Whether {@code body} takes the object form -- §8.3 asks the same question to judge class stability. */
    static boolean isObjectForm(TsonSchema schema, MapBody body) {
        return scalarKeyParser(schema, body.keyType().name()).isPresent();
    }

    /**
     * {@code K}'s own parser when {@code K} is a type a single scalar token denotes -- §6.5's test for the object
     * form, and the same line [TSON-SCHEMA] §5.2 draws for value modifiers. Empty for every other {@code K}, which
     * takes the pairs form. The kernel's {@code value} and {@code void} fall to pairs by declining a parser rather
     * than by being listed: neither has a content grammar for a key token to face.
     */
    private static Optional<AtomType<?>> scalarKeyParser(TsonSchema schema, String keyTypeName) {
        ReferenceChain.Resolved terminal = ReferenceChain.terminal(schema, keyTypeName).orElseThrow(() ->
                new IllegalStateException("'" + keyTypeName + "' does not resolve -- linking should have refused it"));
        return terminal.definition().body() instanceof Atom atom
                ? AtomParsers.forType(terminal.name(), atom)
                : Optional.empty();
    }
}
