package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * A Class 2 (schema-validated) reading failure -- e.g. a required field missing from a record.
 * Unlike {@link TsonParseException}, which always has a real {@link Position} in hand at the point
 * it's thrown, a compiled {@link TsonValueReader} is built once and reused across many, unrelated
 * reads, so it never holds the position table a specific read's own source document was parsed
 * with. This carries what it *does* have instead, for a caller who does hold that table to resolve
 * afterward:
 *
 * <ul>
 *   <li>{@link #dataValue()} -- the enclosing value's own {@code CoreValue} identity (never the
 *   missing field's own value, which by definition doesn't exist). A caller holding the original
 *   {@code TsonDataParser}/{@code TsonSchemaParser}'s own {@code positions()}/{@code
 *   declarationPositions()} table can look this exact object up by reference identity to find where
 *   in the *data* source it came from.</li>
 *   <li>{@link #schemaPosition()} -- where the failing record was declared in its *schema* source,
 *   already resolved (no further lookup needed) since {@code schema.meta.TypeDefinition} carries
 *   this directly once resolved from real source text. Absent for a schema resolved without a real
 *   position (e.g. a materialized/synthesized entry, or a hand-built {@code TypeDefinition} with no
 *   source text at all).</li>
 * </ul>
 */
public final class TsonReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient CoreValue dataValue;
    private final transient Optional<SourcePosition> schemaPosition;

    public TsonReadException(String message, CoreValue dataValue, Optional<SourcePosition> schemaPosition) {
        super(message);
        this.dataValue = dataValue;
        this.schemaPosition = schemaPosition;
    }

    public CoreValue dataValue() {
        return dataValue;
    }

    public Optional<SourcePosition> schemaPosition() {
        return schemaPosition;
    }
}
