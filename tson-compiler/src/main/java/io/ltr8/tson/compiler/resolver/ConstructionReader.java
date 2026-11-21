package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.schema.meta.Top;

/**
 * Reads a construction, {@code !C value}, through the compiled reader of its head: what {@link
 * DefinitionResolver#bindAtomInstance} binds a constructor application or atom refinement with. {@code type} is the
 * head's name ({@code value.typeRef()}'s own value, already attached by the caller), {@code value} the
 * already-normalized (record-form) data to read.
 *
 * <p>A required constructor parameter of {@link DefinitionResolver}, not threaded per call --
 * {@link DefinitionResolver} has no dependency on {@code reader} at all; a caller with compiled vocabulary (e.g.
 * {@link SchemaResolver}) supplies one of these wrapping it (typically a one-line lambda, {@code (type, value) ->
 * (Top) vocabulary.reader(type).read(value)}). Outside the kernel's own resolution every body is read at a checkpoint
 * of the document being resolved ({@link WaitOrder#read}), which makes an entry a read reaches ready before reading
 * again.
 */
@FunctionalInterface
interface ConstructionReader {

    Top read(String type, DataValue value);

    /**
     * Reads a value entry's payload, {@code name => !T { ... }} with {@code T} no constructor, against the type
     * {@code type}: the value it is, bound where a class binds the type and held as a tree where none does. Both
     * resolutions' readers ({@link SchemaResolver}'s, {@link KernelBootstrapResolver}'s) read one, so the default is a
     * reader built for a resolution that never reaches a value.
     */
    default Object readValue(String type, DataValue value) {
        throw new IllegalStateException("'!" + type + " { ... }' reached a construction reader that reads no "
                + "values -- every resolution's reader reads one");
    }
}
