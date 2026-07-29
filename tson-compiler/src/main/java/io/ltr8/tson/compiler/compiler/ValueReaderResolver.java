package io.ltr8.tson.compiler.compiler;

import io.ltr8.tson.compiler.TsonValueReader;

/**
 * Resolves a schema type name to its own already-compiled {@link TsonValueReader} -- what a
 * composite reader's own child-field resolution calls to reach the reader for a field's own type
 * (e.g. {@link RecordAbstractReader#buildFields}, an array/map/tuple reader's own element/key/value
 * resolution in its constructor). In practice this is always backed by {@link TsonSchemaCompiler}'s
 * own per-compilation resolution, so a child lookup recurses back into the same eager, cycle-safe
 * machinery the top-level {@link TsonSchemaCompiler#compile} walk itself uses -- an implementation
 * of this interface never needs to know or care whether it's being called recursively from a child
 * field or from the top level.
 */
interface ValueReaderResolver {
    TsonValueReader<?> resolve(String typeName);
}
