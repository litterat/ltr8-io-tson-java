package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledMetaSchema;

/**
 * Finds the {@link ValueReaderFactory} registered for a constructor name (e.g. {@code "record"}, {@code
 * "array"}, {@code "integer_type"}). {@link ValueReaderFactoryRegistry} is the one real implementation --
 * a fixed table covering meta-kernel/meta.tn's own closed constructor vocabulary, one instance per mode
 * ({@link ValueReaderFactoryRegistry#dom()}/{@link ValueReaderFactoryRegistry#bind}). A governed compile
 * dispatches each entry's own resolved body to the right factory by its constructor name (scoped through
 * the governing {@link TsonCompiledMetaSchema}); a standalone compile dispatches through one such set
 * directly. Internal to {@code tson-compiler} -- a consumer picks a read mode by which {@code
 * TsonCompiledSchemaRegistry} they hold, never by naming this type.
 */
public interface ValueReaderFactoryResolver {
    ValueReaderFactory resolve(String name);
}
