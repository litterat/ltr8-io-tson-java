package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses and validates against a reference that names types -- an instance of the meta-kernel's {@code reference}
 * ([TSON-SCHEMA] §4.2) whose {@code of} is a constructor, spelled {@code kind<record>} or {@code ref<record>} alike.
 * A value is a type's name or an application, read by {@link ReferenceGrammar} into the {@link TypeRef} it is, and
 * then judged a member: the entry it denotes must be one of the reference's linked members. The value is the
 * reference, never a value of the type it names; a reader rolls a name token or a reference's tree into the one
 * text this reads.
 *
 * <p><b>The value finds what it names.</b> A reference read here carries the closure the members were computed over
 * ({@link ReferenceBody#closure}), so {@link TypeRef#referent} answers with the entry it denotes.
 *
 * <p><b>An application is judged through the entry it denotes</b>, resolved bottom-up, level by level, against the
 * applications the closure closed ({@link ReferenceBody#entryOf}). A schema loads only when every application in it
 * closes, so data never creates one: an application nothing in the closure closed names no type and is refused, as a
 * name no member is. A type's name is its path from the root, {@code order} or {@code geo.address} for a member of a
 * schema map nested as an entry, and is judged as one name.
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
public record KindParser(ReferenceBody kind, boolean judgesMembers) implements AtomTypeParser<TypeRef> {

    /** A kind read under the schema whose closure its members were computed over. */
    public KindParser(ReferenceBody kind) {
        this(kind, true);
    }

    /** {@code kind}, read where a name resolves in the document being read: the grammar alone. */
    public static KindParser namesOnly(ReferenceBody kind) {
        return new KindParser(kind, false);
    }

    @Override
    public TypeRef read(String text) {
        TypeRef reference = ReferenceGrammar.parse(text);
        if (reference.arguments().isEmpty()) {
            // A name is read as the kernel's type_name reads each segment, into its normalization form.
            List<String> segments = new ArrayList<>(reference.segments().size());
            for (String segment : reference.segments()) {
                segments.add(IdentifierParser.IDENTIFIER.read(segment));
            }
            reference = TypeRef.of(String.join(".", segments));
        }
        if (!judgesMembers) {
            return reference;
        }
        TypeRef read = reference;
        String entry = kind.entryOf(read).orElseThrow(() -> new AtomValidationException("'" + read.text()
                + "' names no type this schema closes -- an application in data names the entry the schema closed "
                + "it to, and nothing in this closure applies it; one of " + kind.shownMembers(),
                "one of (" + String.join(", ", kind.shownMembers()) + ")"));
        if (kind.memberNames().contains(entry)) {
            return reference.withClosure(kind.closure());
        }
        // An application is named as written, never by the entry minted for it.
        throw new AtomValidationException("'" + reference.text() + "' is not a member of this kind -- expected the "
                + "name of a type whose value IS-A '" + kind.of() + "', one of " + kind.shownMembers(),
                "one of (" + String.join(", ", kind.shownMembers()) + ")");
    }

    @Override
    public String write(TypeRef value) {
        return value.text();
    }

    /** A kind reads to its reference: a {@code TypeRef} target receives it, and a string target its canonical text. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return target == String.class ? converting(TypeRef::text, text -> text) : natural(TypeRef.class, target);
    }
}
