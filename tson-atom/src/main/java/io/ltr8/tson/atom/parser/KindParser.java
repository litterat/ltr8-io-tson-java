package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.schema.meta.ReferenceBody;

import java.util.Optional;

/**
 * Parses and validates against a reference that names types -- an instance of the meta-kernel's {@code reference}
 * ([TSON-SCHEMA] §4.2) whose {@code of} is a constructor, spelled {@code kind<record>} or {@code ref<record>} alike.
 * A value is a type name: §7.7's grammar, read as the kernel's {@code type_name} reads it ({@link
 * IdentifierParser#IDENTIFIER}), and then one of the reference's linked members. The value is the name, never a value
 * of the type it names.
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
public record KindParser(ReferenceBody kind, boolean judgesMembers) implements AtomTypeParser<String> {

    /** A kind read under the schema whose closure its members were computed over. */
    public KindParser(ReferenceBody kind) {
        this(kind, true);
    }

    /** {@code kind}, read where a name resolves in the document being read: the type-name grammar alone. */
    public static KindParser namesOnly(ReferenceBody kind) {
        return new KindParser(kind, false);
    }

    @Override
    public String read(String text) {
        String name = IdentifierParser.IDENTIFIER.read(text);
        if (!judgesMembers || kind.memberNames().contains(name)) {
            return name;
        }
        throw new AtomValidationException("'" + name + "' is not a member of this kind -- expected the name of a "
                + "type whose value IS-A '" + kind.of() + "', one of " + kind.memberNames(),
                "one of (" + String.join(", ", kind.memberNames()) + ")");
    }

    @Override
    public String write(String value) {
        return value;
    }

    /** A kind reads to the name, so a string target is its own value and nothing else is. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return natural(String.class, target);
    }
}
