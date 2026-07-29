package io.ltr8.tson.compiler.config;

import io.ltr8.tson.compiler.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.compiler.ValueReaderFactory;
import io.ltr8.tson.compiler.compiler.ValueReaderFactoryRegistry;

/**
 * Finds the {@link ValueReaderFactory} registered for a constructor name (e.g. {@code "record"},
 * {@code "array"}, {@code "integer_type"}). {@link ValueReaderFactoryRegistry} is the one real
 * implementation -- a fixed table covering meta-kernel/meta.tn1's own closed constructor vocabulary,
 * one instance per mode ({@link ValueReaderFactoryRegistry#dom()}/{@link
 * ValueReaderFactoryRegistry#bind}). {@link TsonCompiledMetaSchema#create} is the sole caller,
 * dispatching a governed schema's own entry to the right factory by its resolved body's own
 * constructor name.
 */
public interface ValueReaderFactoryResolver {
    ValueReaderFactory resolve(String name);
}
