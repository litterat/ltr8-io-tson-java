package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Map;
import java.util.Optional;

/**
 * The "compile" stage's own noun -- an already-built, immutable {@code Map<String,
 * TsonTypeReader<?>>} paired with the {@link TsonLinkedSchema} it was compiled from, produced by
 * {@link TsonSchemaCompiler#compile} and never constructed directly outside this package. Holds no
 * build logic of its own; all the actual compile-time work (the eager walk, cycle detection,
 * per-entry build-failure deferral) lives in {@link TsonSchemaCompiler} itself, matching the
 * verb/noun split this project's own pipeline vocabulary uses everywhere else ({@code
 * TsonSchemaLinker}/{@code TsonLinkedSchema}, {@code TsonSchemaResolver}/its own resolved {@code
 * TsonSchema}).
 *
 * <p>The compile output for *any* resolved schema. A meta-layer schema (one whose {@code !!meta} is
 * meta-kernel) compiles to the {@link TsonCompiledMetaSchema} subtype, which adds the scoped
 * constructor vocabulary needed to *govern* another schema's compilation; every other schema compiles
 * to a bare {@code TsonCompiledSchema}, which can be read but never used as a governing meta. So the
 * type itself records whether a compiled schema is allowed to govern.
 *
 * <p>{@link #get} reads *any* entry, unscoped -- unlike {@link TsonCompiledMetaSchema#reader}, which
 * is deliberately scoped to only the entries a governing meta-schema itself declares as constructors
 * (§3.3.1's structure-namespace rule).
 */
public sealed class TsonCompiledSchema permits TsonCompiledMetaSchema {

    private final TsonLinkedSchema linkedSchema;
    private final Map<String, TsonTypeReader<?>> entries;

    public TsonCompiledSchema(TsonLinkedSchema linkedSchema, Map<String, TsonTypeReader<?>> entries) {
        this.linkedSchema = linkedSchema;
        this.entries = entries;
    }

    /**
     * The {@link TsonLinkedSchema} this was compiled from -- package-private, so the {@link
     * TsonCompiledMetaSchema} subtype can pass it to {@code super} when built from an existing base schema.
     */
    TsonLinkedSchema linkedSchema() {
        return linkedSchema;
    }

    /**
     * The compiled readers, by entry name -- package-private, same reason as {@link #linkedSchema()}. The map
     * is already immutable ({@link TsonSchemaCompiler} copies it before construction).
     */
    Map<String, TsonTypeReader<?>> entries() {
        return entries;
    }

    public TsonTypeReader<?> get(String typeName) {
        TsonTypeReader<?> parser = entries.get(typeName);
        if (parser == null) {
            throw new IllegalArgumentException("'" + typeName + "' is not in this compiled schema");
        }
        return parser;
    }

    /**
     * The reader for {@code typeName}, or empty if this schema has none -- the non-throwing
     * counterpart to {@link #get}, for a caller that treats an absent entry as a normal outcome
     * (e.g. building a governing meta's scoped constructor vocabulary, where a placeholder schema
     * legitimately has no readers yet).
     */
    public Optional<TsonTypeReader<?>> find(String typeName) {
        return Optional.ofNullable(entries.get(typeName));
    }

    /**
     * The resolved {@link TsonSchema} this was compiled from -- e.g. so a caller that only has a
     * compiled reader (such as {@code TsonCompiledSchemaLoader}) can still reach its own resolved
     * {@code entries()} without a separate lookup.
     */
    public TsonSchema schema() {
        return linkedSchema.schema();
    }
}
