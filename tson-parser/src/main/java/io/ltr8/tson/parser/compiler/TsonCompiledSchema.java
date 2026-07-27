package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Map;

/**
 * A compiled {@link TsonLinkedSchema} -- {@code Map<String, TypeDefinition>} lifted to {@code
 * Map<String, TsonValueReader<?>>}, where every reader already holds real Java object references to
 * its own child readers (a {@link ParserHandle}) rather than resolving names again at read time.
 * {@link SchemaValidatingParser}, the Class 2 (schema-validating) data parser, is built on top of
 * this, not directly on a {@link TsonSchema}.
 *
 * <p>Produced by {@link TsonSchemaCompiler#compile}, never constructed directly -- an immutable,
 * already-built value with no build logic of its own; see {@link TsonSchemaCompiler} for how
 * compilation works. {@link #schema()} unwraps to the bare {@link TsonSchema} for the common case
 * of reading resolved {@code entries()}; there's no accessor for the linked schema itself, since
 * nothing needs it back out once compiled.
 */
public final class TsonCompiledSchema {

    private final TsonLinkedSchema linkedSchema;
    private final Map<String, TsonValueReader<?>> entries;

    TsonCompiledSchema(TsonLinkedSchema linkedSchema, Map<String, TsonValueReader<?>> entries) {
        this.linkedSchema = linkedSchema;
        this.entries = entries;
    }

    public TsonValueReader<?> get(String typeName) {
        TsonValueReader<?> parser = entries.get(typeName);
        if (parser == null) {
            throw new IllegalArgumentException("'" + typeName + "' is not in this compiled schema");
        }
        return parser;
    }

    /** The resolved {@link TsonSchema} this was compiled from -- e.g. so a caller that only has a compiled reader (such as {@code TsonCompiledSchemaLoader}) can still reach its own resolved {@code entries()} without a separate lookup. */
    public TsonSchema schema() {
        return linkedSchema.schema();
    }
}
