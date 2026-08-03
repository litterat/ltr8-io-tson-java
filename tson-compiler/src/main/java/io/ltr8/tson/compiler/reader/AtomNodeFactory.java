package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * Tree mode: decorates a leaf {@link ValueReaderFactory} (an atom/enum factory) so the reader it builds
 * yields a {@link io.ltr8.tson.tree.TsonNode} via {@link AtomNodeReader} -- the entry's own name is
 * the leaf's declared type-ref. Applied to every atom-family factory when building the tree factory table
 * (see {@link ValueReaderFactoryRegistry#tree()}); DOM/object-binding modes apply the identity instead.
 */
record AtomNodeFactory(ValueReaderFactory delegate) implements ValueReaderFactory {

    @Override
    public TsonValueReader<?> create(String name, TypeDefinition typeDefinition, TsonValueReaderResolver resolver) {
        return new AtomNodeReader(delegate.create(name, typeDefinition, resolver), name);
    }
}
