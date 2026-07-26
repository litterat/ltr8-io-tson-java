package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.schema.TsonSchema;

import java.util.Map;

/**
 * A compiled {@link TsonSchema} -- {@code Map<String, TypeDefinition>} lifted to {@code Map<String,
 * TsonSchemaTypeParser<?>>}, where every parser's own references to other entries are real Java object
 * references (a {@link ParserHandle}), not further name lookups. This is the "compile the schema
 * once, read many data documents against it fast" layer sitting on top of {@code DefinitionResolver}'s
 * own per-declaration resolution and {@code TsonSchemaRegistry}'s whole-schema materialization/
 * validation -- {@link SchemaValidatingParser}, the actual Class 2 (schema-validating) data parser, is built
 * on top of a {@code TsonCompiledSchema}, not directly on {@code TsonSchema}.
 *
 * <p>Produced by {@link TsonSchemaCompiler#compile}, never constructed directly -- {@code
 * TsonSchemaCompiler} is the verb, this class is the noun it produces, and (2026-07-27, on the
 * user's own explicit direction) this class holds nothing else: no build logic, no cycle-detection
 * bookkeeping -- just the already-finished result of one {@link TsonSchemaCompiler#compile} call,
 * an immutable {@code Map<String, TsonSchemaTypeParser<?>>} handed in fully built. See {@link
 * TsonSchemaCompiler}'s own Javadoc for how compilation itself works (eager building, cycle
 * detection, per-entry {@link ErrorParser} deferral) -- none of that lives here anymore.
 */
public final class TsonCompiledSchema {

    private final TsonSchema schema;
    private final Map<String, TsonSchemaTypeParser<?>> entries;

    TsonCompiledSchema(TsonSchema schema, Map<String, TsonSchemaTypeParser<?>> entries) {
        this.schema = schema;
        this.entries = entries;
    }

    public TsonSchemaTypeParser<?> get(String typeName) {
        TsonSchemaTypeParser<?> parser = entries.get(typeName);
        if (parser == null) {
            throw new IllegalArgumentException("'" + typeName + "' is not in this compiled schema");
        }
        return parser;
    }

    /** The resolved {@link TsonSchema} this was compiled from -- e.g. so a caller that only has a compiled reader (such as {@code SchemaCoordinator}) can still reach its own resolved {@code entries()} without a separate lookup. */
    public TsonSchema schema() {
        return schema;
    }
}
