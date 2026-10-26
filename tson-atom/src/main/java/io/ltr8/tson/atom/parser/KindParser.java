package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.schema.meta.KindBody;

import java.util.Optional;

/**
 * Parses and validates against a kind -- an instance of the meta-kernel's {@code kind_type} ([TSON-SCHEMA] §4.2),
 * the atom whose values are the names of the types built with one constructor. A value is a type name: §7.7's
 * grammar, read as the kernel's {@code type_name} reads it ({@link IdentifierParser#IDENTIFIER}), and then a
 * member of the kind's linked {@code members}. The value is the name, never a value of the type it names.
 *
 * <p><b>Membership is judged where the names resolve.</b> A kind's members are the names of the governing
 * schema's closure, so a name read under that schema is a member or refused here, as an enum refuses a label it
 * does not list. Where the governing schema is a meta layer the document is itself a schema, and a name it writes
 * resolves in that document's own namespace, which the closure does not hold: {@link #namesOnly} reads the
 * grammar alone and the schema's own link judges the name a member, after resolution.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5), like {@link EnumParser}: the members are the
 * closure's.
 */
public record KindParser(KindBody kind, boolean judgesMembers) implements AtomTypeParser<String> {

    /** A kind read under the schema whose closure its members were computed over. */
    public KindParser(KindBody kind) {
        this(kind, true);
    }

    /** {@code kind}, read where a name resolves in the document being read: the type-name grammar alone. */
    public static KindParser namesOnly(KindBody kind) {
        return new KindParser(kind, false);
    }

    @Override
    public String read(String text) {
        String name = IdentifierParser.IDENTIFIER.read(text);
        if (!judgesMembers || kind.memberNames().contains(name)) {
            return name;
        }
        throw new AtomValidationException("'" + name + "' is not a member of this kind -- expected the name of a "
                + "type built with '" + kind.of() + "', one of " + kind.memberNames(),
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
