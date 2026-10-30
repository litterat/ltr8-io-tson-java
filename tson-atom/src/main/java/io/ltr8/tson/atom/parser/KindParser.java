package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

/**
 * Parses and validates against a reference that names types -- an instance of the meta-kernel's {@code reference}
 * ([TSON-SCHEMA] §4.2) whose {@code of} is a constructor, spelled {@code kind<record>} or {@code ref<record>} alike.
 * A value is a type name: §7.7's grammar, read as the kernel's {@code type_name} reads it ({@link
 * IdentifierParser#IDENTIFIER}), and then one of the reference's linked members. The value is the name, never a value
 * of the type it names.
 *
 * <p><b>An application is a value too</b>, written quoted ({@code "set<text>"}) and read by {@link ReferenceGrammar}
 * (SPEC-FEEDBACK.md #12). It reads to its canonical text and is judged through the entry it denotes: the one the
 * closure records it as (a declaration {@code names => set<text>}, an instantiation entry), which must be a member.
 * An application the closure records no entry for is this library's gap ({@link UnsupportedOperationException}):
 * an expansion nothing declares is named by its content, which a data read does not compute.
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
        if (text.indexOf('<') >= 0) {
            return application(ReferenceGrammar.parse(text));
        }
        String name = IdentifierParser.IDENTIFIER.read(text);
        if (!judgesMembers || kind.memberNames().contains(name)) {
            return name;
        }
        throw notAMember("'" + name + "'");
    }

    private String application(TypeRef application) {
        if (!judgesMembers) {
            return application.text();
        }
        String entry = kind.entryOf(application).orElseThrow(() -> new UnsupportedOperationException("'"
                + application.text() + "' is an application this closure records no entry for -- a data read "
                + "resolves an application to the entry its closure declares for it ('name => "
                + application.text() + "'), and an expansion nothing declares is not yet found"));
        if (kind.memberNames().contains(entry)) {
            return application.text();
        }
        throw notAMember("'" + application.text() + "', the entry '" + entry + "',");
    }

    private AtomValidationException notAMember(String what) {
        return new AtomValidationException(what + " is not a member of this kind -- expected the name of a type "
                + "whose value IS-A '" + kind.of() + "', one of " + kind.memberNames(),
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
