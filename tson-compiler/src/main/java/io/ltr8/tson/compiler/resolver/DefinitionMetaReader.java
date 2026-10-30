package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.schema.meta.Top;

/**
 * What {@link DefinitionResolver#bindAtomInstance} uses to bind a constructor-application/atom-
 * refinement value against its own constructor's compiled reader -- {@code type} is the constructor
 * name ({@code value.typeRef()}'s own value, already attached by the caller), {@code value} the
 * already-normalized (record-form) data to read.
 *
 * <p>A required constructor parameter of {@link DefinitionResolver}, not threaded per call --
 * {@link DefinitionResolver} has no dependency on {@code reader} at all; a caller
 * with a real compiled reader (e.g. {@link SchemaResolver}) supplies one of these wrapping it
 * (typically a one-line lambda, {@code (type, value) -> (Top) metaParser.get(type).read(value)}).
 * A head declared by the document being resolved is read through readers compiled from its entries ready so far
 * ({@link WaitOrder#read}), which makes an entry a read reaches ready before reading again -- in the kernel's
 * bootstrap, every head.
 */
@FunctionalInterface
interface DefinitionMetaReader {

    Top read(String type, DataValue value);

    /**
     * Reads a value entry's payload, {@code name => !T { ... }} with {@code T} no constructor, against the type
     * {@code type}: the value it is, bound where a class binds the type and held as a tree where none does. A reader
     * over vocabulary with no value entries (the kernel's bootstrap) has none to read.
     */
    default Object readValue(String type, DataValue value) {
        throw new UnsupportedOperationException("'!" + type + " { ... }' is a value of the type '" + type
                + "', and this resolution reads no value entries");
    }
}
