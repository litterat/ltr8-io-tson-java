package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/**
 * Parses a reference that names values -- an instance of the kernel's {@code ref} whose {@code of} is no constructor,
 * {@code meta.ref<method>}. A value is a qualified name, segments between dots, each a [TSON-DATA] §7.7 identifier,
 * read into the {@link ValueRef} a host receives.
 *
 * <p><b>The value finds what it names</b> where the reference was linked over a whole closure ({@link
 * ReferenceBody#values}): the name is judged by what it reaches there, as the linker judges a value body's names
 * ([TSON-SCHEMA] §4.2, §5.12), and the {@link ValueRef} returned has that value delivered. A name reaching nothing, a
 * type, or a value whose type is no {@code of} is refused as a constraint the value breaks.
 *
 * <p><b>A name in a schema document resolves in that schema</b>, which the closure the reference was linked over does
 * not hold: {@link #namesOnly} reads the grammar alone, into a {@link ValueRef} that schema's link delivers. A
 * reference whose schema is still resolving has nothing to reach yet and reads the grammar alone too.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5), like {@link TypeNameRefParser}.
 */
public record RefParser(ReferenceBody reference, boolean judgesValues) implements AtomTypeParser<ValueRef<Object>> {

    /** A reference read under the schema whose closure it was linked over. */
    public RefParser(ReferenceBody reference) {
        this(reference, true);
    }

    /** {@code reference}, read where a name resolves in the document being read: the grammar alone. */
    public static RefParser namesOnly(ReferenceBody reference) {
        return new RefParser(reference, false);
    }

    @Override
    public ValueRef<Object> read(String text) {
        TypeRef read = ReferenceGrammar.parse(text);
        ValueRef<Object> cell = ValueRef.to(read.name());
        Optional<ReferenceBody.Values> values = judgesValues ? reference.values() : Optional.empty();
        if (values.isEmpty()) {
            return cell;
        }
        try {
            cell.deliver(values.get().reach("this reference", read));
        } catch (SchemaValidationException refused) {
            throw new AtomValidationException(refused.getMessage(),
                    "the name of a value of '" + reference.of().text() + "'");
        }
        return cell;
    }

    @Override
    public String write(ValueRef<Object> value) {
        return value.name();
    }

    /**
     * A reference reads to a {@link ValueRef}, and a string target receives its name, judged alike. A {@code TypeRef}
     * target receives nothing: a value is no type, and its name binds as a {@code ValueRef} or a string.
     */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        if (target == String.class) {
            return converting(ValueRef::name, text -> text);
        }
        return natural(ValueRef.class, target);
    }
}
