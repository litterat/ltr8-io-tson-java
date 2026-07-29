package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Map;

/**
 * The "compile" stage's own noun -- an already-built, immutable {@code Map<String,
 * TsonValueReader<?>>} paired with the {@link TsonLinkedSchema} it was compiled from, produced by
 * {@link TsonSchemaCompiler#compile} and never constructed directly outside this package. Holds no
 * build logic of its own; all the actual compile-time work (the eager walk, cycle detection,
 * per-entry build-failure deferral) lives in {@link TsonSchemaCompiler} itself, matching the
 * verb/noun split this project's own pipeline vocabulary uses everywhere else ({@code
 * TsonSchemaLinker}/{@code TsonLinkedSchema}, {@code TsonSchemaResolver}/its own resolved {@code
 * TsonSchema}).
 *
 * <p>{@link #get} reads *any* entry, unscoped -- unlike {@link TsonCompiledMetaSchema#reader}, which
 * is deliberately scoped to only the entries a governing meta-schema itself declares as constructors
 * (§3.3.1's structure-namespace rule). A caller holding a bare {@code TsonCompiledSchema} directly
 * (rather than the {@link TsonCompiledMetaSchema} that wraps one) has already opted out of that
 * scoping -- e.g. a caller reading an arbitrary resolved entry directly, or {@link
 * TsonCompiledMetaSchema} itself, which needs unscoped access while first building its own {@code
 * reader()} lookup.
 */
public final class TsonCompiledSchema {

    private final TsonLinkedSchema linkedSchema;
    private final Map<String, TsonValueReader<?>> entries;

    public TsonCompiledSchema(TsonLinkedSchema linkedSchema, Map<String, TsonValueReader<?>> entries) {
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
