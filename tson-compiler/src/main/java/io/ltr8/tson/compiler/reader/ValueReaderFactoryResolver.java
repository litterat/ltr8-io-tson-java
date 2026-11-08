package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledVocabulary;

/**
 * Finds the {@link ValueReaderFactory} registered for a constructor name (e.g. {@code "meta.record"}, {@code
 * "meta.array"}, {@code "numeric.integer_type"}). {@link ValueReaderFactoryRegistry} is the one real implementation -- a
 * fixed table covering the kernel's own closed constructor vocabulary, one instance per mode ({@link
 * ValueReaderFactoryRegistry#tree}/{@link ValueReaderFactoryRegistry#bind}). A compile dispatches each entry's own
 * resolved body to the right factory by its constructor name, through one such set; {@link TsonCompiledVocabulary}
 * stands an unbacked constructor in as a gap. Internal to {@code tson-compiler} -- a consumer picks a read mode by
 * which {@code TsonCompiledSchemaRegistry} they hold, never by naming this type.
 */
public interface ValueReaderFactoryResolver {
    ValueReaderFactory resolve(String name);
}
