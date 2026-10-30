package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/**
 * Parses a reference that names values -- an instance of the meta-kernel's {@code reference} whose {@code of} is no
 * constructor, spelled {@code ref<method>} or {@code kind<method>} alike. A value is a qualified name, segments between
 * dots, each a [TSON-DATA] §7.7 identifier, read into the {@link TypeRef} it is; a value is no type, so it takes no
 * argument list. What the name reaches is judged where it resolves, when the schema links, never here, so this
 * parser reads schema documents alone: a data document is never linked, and the reference it returns names nothing
 * yet ({@link TypeRef#referent} is empty; the linker delivers the value into a {@code Ref} host).
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5), like {@link KindParser}.
 */
public record RefParser(ReferenceBody reference) implements AtomTypeParser<TypeRef> {

    @Override
    public TypeRef read(String text) {
        TypeRef read = ReferenceGrammar.parse(text);
        if (!read.arguments().isEmpty()) {
            throw new AtomParseException("'" + text + "' is no qualified name -- a value is named, never applied, "
                    + "so it takes no argument list", "a qualified name");
        }
        return read;
    }

    @Override
    public String write(TypeRef value) {
        return value.text();
    }

    /** A reference reads to its reference: a {@code TypeRef} target receives it, a string target its name. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return target == String.class ? converting(TypeRef::text, text -> text) : natural(TypeRef.class, target);
    }
}
