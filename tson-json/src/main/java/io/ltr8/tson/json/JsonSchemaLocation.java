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
 * identity and position on itself as the read descends ({@link #anchoredOn}), and every other declaration
 * offers its own only as a seed for a value nothing encloses.
 *
 * <p>The peer of {@code tson-compiler}'s {@code SchemaLocation}, and a separate type on the same terms as the
 * rest of this stack ({@code docs/json-encoding.md}): the schema-directed readers here are {@code
 * tson-json}'s own, and this is the piece of them that a consolidation would merge first, both being pure
 * values over {@code tson-base} types.
 */
public record JsonSchemaLocation(String schemaId, String pointer, Optional<SourcePosition> position) {

    /** The location of one top-level declaration -- the root a read entering through {@code declaration} starts from. */
    public static JsonSchemaLocation of(String schemaId, String declaration, Optional<SourcePosition> position) {
        return new JsonSchemaLocation(schemaId, "/" + declaration, position);
    }

    /** This location one declared member deeper, {@code name} RFC 6901-escaped into the pointer. */
    public JsonSchemaLocation field(String name) {
        return new JsonSchemaLocation(schemaId, pointer + "/" + name.replace("~", "~0").replace("/", "~1"), position);
    }

    /**
     * As {@link #field(String)}, taking the field's own declaration line where it has one -- so a diagnostic
     * against {@code /person/age} is located at {@code age} rather than at {@code person}'s line. A field with
     * no position of its own leaves the enclosing declaration's in place, which is the honest answer for a
     * schema this processor never saw source text for.
     */
    public JsonSchemaLocation field(String name, Optional<SourcePosition> at) {
        JsonSchemaLocation stepped = field(name);
        return at.isPresent() ? new JsonSchemaLocation(stepped.schemaId, stepped.pointer, at) : stepped;
    }

    /**
     * This location's pointer over {@code declaration}'s identity and position -- what a record offers as it
     * begins reading, so the pointer keeps the path the read took while the id and line follow the
     * declaration that actually owns the members being read. A declaration with no position of its own
     * contributes none, leaving the enclosing one's in place.
     */
    public JsonSchemaLocation anchoredOn(JsonSchemaLocation declaration) {
        return new JsonSchemaLocation(declaration.schemaId, pointer,
                declaration.position.isPresent() ? declaration.position : position);
    }
}
