package io.ltr8.tson.compiler.compiler;

import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * Builds the {@link TsonValueReader} for one resolved schema entry -- the unit of work {@link
 * ValueReaderFactoryRegistry} dispatches to, keyed by the entry's own resolved body's constructor
 * name (e.g. {@code "record"}, {@code "array"}, {@code "integer_type"}). {@code name} is the
 * *declaration's* own name, not necessarily the constructor's -- they coincide only for a
 * meta-schema's own declarations (e.g. {@code "record"} itself); every other real declaration's own
 * name differs from its constructor (e.g. {@code "float32"} constructed via {@code "float_type"}).
 * {@code typeDefinition} is that declaration's own fully-resolved {@link TypeDefinition}, and {@code
 * resolver} is what a composite implementation calls to resolve its own child fields'/elements' own
 * readers.
 */
public interface ValueReaderFactory {
    TsonValueReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderResolver resolver);
}
