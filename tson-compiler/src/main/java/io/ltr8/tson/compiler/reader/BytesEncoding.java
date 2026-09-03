package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.compiler.atom.BytesParser;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.meta.BytesType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Resolves meta.tn's {@code @bytes_encoding} directive: which RFC 4648 alphabet a text encoding spells a
 * {@code bytes} value in.
 *
 * <p><b>Nearest-first</b>, as the directive's own {@code @doc} states -- the field, then the field's type's
 * definition walking its supertypes, then base64. The order is what makes the directive usable at either
 * grain: a schema designer annotates one field where one field differs, or annotates a declaration where a
 * whole type is spelled that way, and the field wins where both speak.
 *
 * <p><b>It is a directive, not a facet.</b> The alphabet is no part of what a {@code bytes} value <em>is</em>
 * -- the same octets are {@code "3q2+7w=="}, {@code "deadbeef"} and {@code "3WV37Q======"} -- so it neither
 * narrows the type nor participates in identity. An encoding whose values are octets ignores it entirely,
 * which is the property that would have been impossible to state while the alphabet was part of the type.
 */
final class BytesEncoding {

    private BytesEncoding() {
    }

    /** The alphabet the annotations at one position ask for, or empty where they say nothing. */
    static Optional<BytesParser.Encoding> stated(Annotations annotations) {
        if (annotations == null) {
            return Optional.empty();
        }
        return annotations.values().stream()
                .filter(annotation -> annotation.name().equals("bytes_encoding"))
                .flatMap(annotation -> annotation.value().stream())
                .map(String::valueOf)
                .map(BytesEncoding::parse)
                .findFirst();
    }

    /**
     * The alphabet in force for a value of {@code definition}, ignoring any field-level directive: the
     * definition's own, else the nearest one up its supertype chain, else base64.
     *
     * <p>The supertype walk is what lets a refinement inherit the spelling of the type it refines -- a
     * {@code sha256 => !bytes ^ { length: 32 }} under a {@code @bytes_encoding:HEX} declaration is hex
     * without repeating itself.
     */
    static BytesParser.Encoding of(String name, TypeDefinition definition, ValueReaderContext context) {
        return inherited(name, definition, context, 0).orElse(BytesParser.DEFAULT);
    }

    /**
     * A reader for {@code field} when it states a {@code @bytes_encoding} of its own and its type is one
     * this directive governs -- otherwise empty, and the caller takes the shared reader for the type.
     *
     * <p>{@code leaf} is the caller's own mode wrapper: tree mode has to box the atom in a {@code TsonAtom}
     * the way its factory table does, and bind mode must not. Building the reader here without asking would
     * hand tree mode a raw {@code byte[]} where every other leaf gives it a node.
     *
     * <p>A directive on a field whose type is not {@code bytes} is refused at schema load, on {@code @rest}'s
     * terms, so reaching here with one is not this reader's problem to diagnose.
     */
    static Optional<TsonTypeReader<?>> fieldReader(RecordField field, ValueReaderContext context,
            BiFunction<TsonTypeReader<?>, String, TsonTypeReader<?>> leaf) {
        Optional<BytesParser.Encoding> stated = stated(field.annotations());
        if (stated.isEmpty()) {
            return Optional.empty();
        }
        String name = field.type().name();
        TypeDefinition definition = context.schema().entries().get(name);
        if (definition == null || !(definition.body() instanceof BytesType body)) {
            return Optional.empty();
        }
        return Optional.of(leaf.apply(
                AtomTypeReader.of(name, new BytesParser(stated.get(), body), context.locationOf(name, definition)),
                name));
    }

    /**
     * A supertype chain is finite and acyclic by the time a reader is compiled -- the linker refuses a cycle
     * -- but this is reader-construction code reached from a compiled schema, so it counts anyway rather
     * than trusting an invariant enforced elsewhere to hold for a hand-built entry.
     */
    private static Optional<BytesParser.Encoding> inherited(String name, TypeDefinition definition,
            ValueReaderContext context, int depth) {
        if (definition == null || depth > MAX_SUPERTYPE_DEPTH) {
            return Optional.empty();
        }
        // Both of §6's declaration positions. A directive written before a declared name lands on the
        // schema-map *key* and never on the TypeDefinition, so consulting the value alone would silently
        // ignore the spelling an author put in the more natural of the two places -- the same reason
        // TsonSchemaLinker consults both for @disjoint.
        Optional<BytesParser.Encoding> own = stated(context.schema().entries().getAnnotations(name))
                .or(() -> stated(definition.annotations()));
        if (own.isPresent()) {
            return own;
        }
        for (String supertype : definition.supertypes()) {
            Optional<BytesParser.Encoding> above =
                    inherited(supertype, context.schema().entries().get(supertype), context, depth + 1);
            if (above.isPresent()) {
                return above;
            }
        }
        return Optional.empty();
    }

    private static final int MAX_SUPERTYPE_DEPTH = 64;

    /**
     * The annotation's value is checked against {@code base_encoding} by the meta's own compiled reader
     * before it reaches here, so an unrecognised name is a library fault rather than an author error.
     */
    private static BytesParser.Encoding parse(String name) {
        try {
            return BytesParser.Encoding.valueOf(name);
        } catch (IllegalArgumentException notAnAlphabet) {
            throw new IllegalStateException("'" + name + "' is not one of meta.tn's base_encoding members, "
                    + "which the annotation's own type should already have refused", notAnAlphabet);
        }
    }
}
