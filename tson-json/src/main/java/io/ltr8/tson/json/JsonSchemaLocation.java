package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SourcePosition;

import java.util.Optional;

/**
 * Where in a <em>schema</em> a read currently is -- the schema's identity, an RFC 6901 pointer into it, and
 * the declaration's own source position. Three of a {@link Diagnostic}'s nine components, carried together
 * because a read that reports one has always established all three at once.
 *
 * <p><b>The pointer is the path taken, never the leaf it resolves to.</b> A member failing at
 * {@code /person/age} carries person.tn's {@code /person/age}, not core.tn's {@code /int32}: the leaf names a
 * file the author did not write, where the pointer names the field they can edit. So a record re-anchors the
 * identity and position on itself as the read descends, and every other declaration offers its own only as a
 * seed for a value nothing encloses. {@link JsonReadContext} does the descending, stepping the pointer rather
 * than building one of these per field; a location is rendered when a diagnostic needs it.
 *
 * <p>The peer of {@code tson-compiler}'s {@code SchemaLocation}, and a separate type on the same terms as the
 * rest of this stack ({@code design/json-encoding.md}): the schema-directed readers here are {@code
 * tson-json}'s own, and this is the piece of them that a consolidation would merge first, both being pure
 * values over {@code tson-base} types.
 */
public record JsonSchemaLocation(String schemaId, String pointer, Optional<SourcePosition> position) {

    /** The location of one top-level declaration -- the root a read entering through {@code declaration} starts from. */
    public static JsonSchemaLocation of(String schemaId, String declaration, Optional<SourcePosition> position) {
        return new JsonSchemaLocation(schemaId, "/" + declaration, position);
    }
}
