package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.schema.meta.RefBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses and validates against a reference that names types -- an instance of the kernel's {@code reference}
 * ([TSON-SCHEMA] §4.2) whose {@code of} is a constructor, {@code meta.ref<record>}. A value is read by {@link
 * ReferenceGrammar} into the {@link TypeRef} it is, and then judged a member: the name must be one of the
 * reference's linked members. The value is the reference, never a value of the type it names.
 *
 * <p><b>The value finds what it names.</b> A reference read here carries the closure the members were computed over
 * ({@link RefBody#closure}), so {@link TypeRef#referent} answers with the entry it names.
 *
 * <p>A type's name is its path from the root, {@code order} or {@code geo.address} for a member of a schema map
 * nested as an entry, and is judged as one name.
 *
 * <p><b>Membership is judged where the names resolve.</b> A kind's members are the names of the reading
 * document's closure, so a name a data document writes is a member or refused here, as an enum refuses a label it
 * does not list. A name in a schema document resolves in that schema, which the closure does not hold: {@link
 * #namesOnly} reads the grammar alone, and that schema's link, or ingest, judges the name a member ([TSON-SCHEMA]
 * §4.2, §8.1).
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5), like {@link EnumParser}: the members are the
 * closure's.
 */
public record TypeRefParser(RefBody kind, boolean judgesMembers) implements AtomTypeParser<TypeRef> {

    /** A kind read under the schema whose closure its members were computed over. */
    public TypeRefParser(RefBody kind) {
        this(kind, true);
    }

    /** {@code kind}, read where a name resolves in the document being read: the grammar alone. */
    public static TypeRefParser namesOnly(RefBody kind) {
        return new TypeRefParser(kind, false);
    }

    @Override
    public TypeRef read(String text) {
        TypeRef parsed = ReferenceGrammar.parse(text);
        // A name is read as the kernel's meta.type_name reads each segment, into its normalization form.
        List<String> segments = new ArrayList<>(parsed.segments().size());
        for (String segment : parsed.segments()) {
            segments.add(IdentifierParser.IDENTIFIER.read(segment));
        }
        TypeRef reference = TypeRef.of(String.join(".", segments));
        if (!judgesMembers) {
            return reference;
        }
        if (kind.memberNames().contains(reference.name())) {
            return reference.withClosure(kind.closure());
        }
        throw new AtomValidationException("'" + reference.text() + "' is not a member of this kind -- expected the "
                + "name of a type whose value IS-A '" + kind.of().text() + "', one of " + kind.shownMembers(),
                "one of (" + String.join(", ", kind.shownMembers()) + ")");
    }

    @Override
    public String write(TypeRef value) {
        return value.text();
    }

    /** A kind reads to its reference: a {@code TypeRef} target receives it, a string target its canonical text. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        if (target == String.class) {
            return converting(TypeRef::text, text -> text);
        }
        return natural(TypeRef.class, target);
    }
}
