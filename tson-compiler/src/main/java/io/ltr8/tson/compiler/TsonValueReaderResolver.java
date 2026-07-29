package io.ltr8.tson.compiler;

/**
 * Resolves a schema type name to its own already-compiled {@link TsonValueReader} -- what a
 * composite reader's own child-field resolution calls to reach the reader for a field's own type.
 * In practice this is always backed by {@link TsonSchemaCompiler}'s
 * own per-compilation resolution, so a child lookup recurses back into the same eager, cycle-safe
 * machinery the top-level {@link TsonSchemaCompiler#compile} walk itself uses -- an implementation
 * of this interface never needs to know or care whether it's being called recursively from a child
 * field or from the top level.
 */
public interface TsonValueReaderResolver {
    TsonValueReader<?> resolve(String typeName);
}
