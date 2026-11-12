package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.Ref;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/**
 * Parses a reference that names values -- an instance of the kernel's {@code reference} whose {@code of} is no
 * constructor, {@code meta.ref<method>}. A value is a qualified name, segments between dots, each a [TSON-DATA] §7.7
 * identifier, read into the {@link TypeRef} it is; a value is no type, so it takes no argument list.
 *
 * <p><b>The value finds what it names</b> where the reference was linked over a whole closure ({@link
 * ReferenceBody#values}): the name is judged by what it reaches there, as the linker judges a value body's names
 * ([TSON-SCHEMA] §4.2, §5.12), and the {@link TypeRef} returned carries that value as its {@link TypeRef#referent}.
 * A name reaching nothing, a type, or a value whose type is no {@code of} is refused as a constraint the value breaks.
 *
 * <p><b>A name in a schema document resolves in that schema</b>, which the closure the reference was linked over does
 * not hold: {@link #namesOnly} reads the grammar alone, and that schema's link judges the name. A reference whose
 * schema is still resolving has nothing to reach yet and reads the grammar alone too; what it holds is judged when
 * the whole schema links.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5), like {@link TypeNameRefParser}.
 */
public record RefParser(ReferenceBody reference, boolean judgesValues) implements AtomTypeParser<TypeRef> {

    /** A reference read under the schema whose closure it was linked over. */
    public RefParser(ReferenceBody reference) {
        this(reference, true);
    }

    /** {@code reference}, read where a name resolves in the document being read: the grammar alone. */
    public static RefParser namesOnly(ReferenceBody reference) {
        return new RefParser(reference, false);
    }

    @Override
    public TypeRef read(String text) {
        TypeRef read = ReferenceGrammar.parse(text);
        Optional<ReferenceBody.Values> values = judgesValues ? reference.values() : Optional.empty();
        if (values.isEmpty()) {
            return read;
        }
        Object reached;
        try {
            reached = values.get().reach("this reference", read);
        } catch (SchemaValidationException refused) {
            throw new AtomValidationException(refused.getMessage(),
                    "the name of a value of '" + reference.of().text() + "'");
        }
        return read.withClosure(named -> named.equals(read) ? Optional.ofNullable(reached) : Optional.empty());
    }

    @Override
    public String write(TypeRef value) {
        return value.text();
    }

    /**
     * A reference reads to its reference: a {@code TypeRef} target receives it, a string target its name, and a
     * {@link Ref} target a cell holding the value the name reaches, delivered where it reaches one.
     */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        if (target == String.class) {
            return converting(TypeRef::text, text -> text);
        }
        if (target == Ref.class) {
            return bound(text -> cellOf(read(text)), Ref::name);
        }
        return natural(TypeRef.class, target);
    }

    /** {@code read} as a {@link Ref}, the value it reaches delivered, and undelivered where it reaches none here. */
    private static Ref<Object> cellOf(TypeRef read) {
        Ref<Object> cell = Ref.to(read.text());
        read.referent().ifPresent(cell::deliver);
        return cell;
    }
}
