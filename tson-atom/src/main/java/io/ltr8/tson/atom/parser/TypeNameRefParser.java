package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.SiblingRef;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses and validates against a reference that names types -- an instance of the kernel's {@code reference}
 * ([TSON-SCHEMA] §4.2) whose {@code of} is a constructor, {@code meta.ref<record>}. A value is a type's name or an
 * application, read by {@link ReferenceGrammar} into the {@link TypeRef} it is, and then judged a member: the entry it
 * denotes must be one of the reference's linked members. The value is the reference, never a value of the type it
 * names; a reader rolls a name token or a reference's tree into the one text this reads.
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
 * <p><b>A sibling reference is a value where the reference allows one</b> ({@link ReferenceBody#allowSibling}, true
 * only for {@code meta.field_type_ref}, the type of a record field's {@code type}): {@code kind!} is read as written
 * and judged nothing here, since it names a field of the record holding it and no type. Where the facet is false it
 * is refused by the position rule ([TSON-SCHEMA] §5.2) as a constraint the value breaks, not a form it lacks.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5), like {@link EnumParser}: the members are the
 * closure's.
 */
public record TypeNameRefParser(ReferenceBody kind, boolean judgesMembers) implements AtomTypeParser<Reference> {

    /** A kind read under the schema whose closure its members were computed over. */
    public TypeNameRefParser(ReferenceBody kind) {
        this(kind, true);
    }

    /** {@code kind}, read where a name resolves in the document being read: the grammar alone. */
    public static TypeNameRefParser namesOnly(ReferenceBody kind) {
        return new TypeNameRefParser(kind, false);
    }

    @Override
    public Reference read(String text) {
        Reference written = ReferenceGrammar.parseReference(text);
        if (written instanceof SiblingRef sibling) {
            if (kind.allowSibling()) {
                return sibling;
            }
            // The facet's refusal, as NaN's at a float_type withdrawing allow_nan is: the value itself is well formed.
            throw new AtomValidationException(sibling.refusedAt("a position typed by a type reference"),
                    "a type's name");
        }
        TypeRef reference = (TypeRef) written;
        if (reference.arguments().isEmpty()) {
            // A name is read as the kernel's meta.type_name reads each segment, into its normalization form.
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
    public String write(Reference value) {
        return value.text();
    }

    /**
     * A kind reads to its reference: a {@code Reference} target receives it, a string target its canonical text, and a
     * {@code TypeRef} target a type reference, the sibling reference a facet admits being refused there by the
     * position rule.
     */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        if (target == String.class) {
            return converting(Reference::text, text -> text);
        }
        if (target == TypeRef.class && !kind.allowSibling()) {
            return Optional.of(this);
        }
        if (target == TypeRef.class) {
            return bound(text -> switch (read(text)) {
                case TypeRef reference -> reference;
                case SiblingRef sibling -> throw new AtomParseException(sibling.refusedAt("a position typed by a "
                        + "type reference"), "a type's name");
            }, TypeRef::text);
        }
        return natural(Reference.class, target);
    }
}
