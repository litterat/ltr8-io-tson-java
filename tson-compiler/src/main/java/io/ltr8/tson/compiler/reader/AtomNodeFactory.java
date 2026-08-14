package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.tree.TsonValue;

/**
 * Tree mode: decorates a leaf {@link ValueReaderFactory} (an atom/enum factory) so the reader it builds
 * yields a {@link TsonValue} via {@link AtomNodeReader} -- the entry's own name is
 * the leaf's declared type-ref. Applied to every atom-family factory when building the tree factory table
 * (see {@link ValueReaderFactoryRegistry#tree()}); DOM/object-binding modes apply the identity instead.
 */
record AtomNodeFactory(ValueReaderFactory delegate) implements ValueReaderFactory {

    @Override
    public TsonTypeReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderContext context) {
        return new AtomNodeReader(delegate.create(name, typeDefinition, context), name, AnnotationTypes.of(context));
    }
}
