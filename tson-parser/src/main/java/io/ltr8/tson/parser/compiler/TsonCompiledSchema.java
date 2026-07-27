package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Map;

/**
 * A compiled {@link TsonLinkedSchema} -- {@code Map<String, TypeDefinition>} lifted to {@code
 * Map<String, TsonValueReader<?>>}, where every parser's own references to other entries are real
 * Java object references (a {@link ParserHandle}), not further name lookups. This is the "compile
 * the schema once, read many data documents against it fast" layer sitting on top of {@code
 * DefinitionResolver}'s own per-declaration resolution and {@code TsonSchemaRegistry}'s whole-schema
 * materialization/validation -- {@link SchemaValidatingParser}, the actual Class 2
 * (schema-validating) data parser, is built on top of a {@code TsonCompiledSchema}, not directly on
 * {@code TsonSchema}.
 *
 * <p>Produced by {@link TsonSchemaCompiler#compile}, never constructed directly -- {@code
 * TsonSchemaCompiler} is the verb, this class is the noun it produces, and (2026-07-27, on the
 * user's own explicit direction) this class holds nothing else: no build logic, no cycle-detection
 * bookkeeping -- just the already-finished result of one {@link TsonSchemaCompiler#compile} call,
 * an immutable {@code Map<String, TsonValueReader<?>>} handed in fully built. See {@link
 * TsonSchemaCompiler}'s own Javadoc for how compilation itself works (eager building, cycle
 * detection, per-entry {@link ErrorParser} deferral) -- none of that lives here anymore.
 *
 * <p>Holds the {@link TsonLinkedSchema} it was compiled from, not just the bare {@link TsonSchema}
 * -- matching {@link TsonSchemaCompiler#compile}'s own parameter type. {@link #schema()} still
 * unwraps to the bare {@code TsonSchema} for the common case (a caller only wanting to read its
 * resolved {@code entries()}); nothing today needs the linked-proof back out of an already-compiled
 * schema, so there's no {@code linkedSchema()} accessor to go with it.
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
