package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.base.unicode.IdentifierProfile;
import io.ltr8.tson.schema.meta.IdentifierType;

import java.util.Optional;

/**
 * Parses and validates against meta-kernel's {@code identifier_type} constructor -- a UAX #31 identifier profile
 * with {@code text_type}'s facets inside it. The kernel's {@code identifier} instance is the type of every
 * naming position in the series: type names, field names and parameter names through the
 * {@code type_name}/{@code field_name}/{@code param_name} roles, and {@code enum}'s members, its {@code type}.
 * Other instances name an outside system's positions under that system's own profile.
 *
 * <p><b>The profile comes first, then the text facets.</b> A name the profile refuses is a grammar violation
 * whatever its facets say, so a refinement's {@code pattern} or {@code members} is only ever asked of a
 * well-formed name; the facets are {@link TextParser}'s, applied to the same text.
 *
 * <p><b>The profile itself is {@link IdentifierProfile}'s</b>, beside the UCD tables it reads, built once here
 * so a read builds nothing. What is here is only the atom: the rule that a name the profile refuses is a
 * <em>parse</em> failure, which is this position's answer rather than the profile's. Every other caller of the
 * profile -- the lexer's grammar, the schema parser, the resolver, the linker -- owes a different one, which is
 * why the check reports a violation and each caller decides what it becomes.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5) -- like {@link TextParser} and {@link EnumParser},
 * never registered in {@link BuiltinTypeVocabulary} and has no {@code TYPENAME} constant.
 */
public final class IdentifierParser implements AtomTypeParser<String> {

    /** {@code identifier => !identifier_type { continue_add: "-" }} -- [TSON-DATA] §7.7's identifier. */
    public static final IdentifierParser IDENTIFIER = new IdentifierParser(IdentifierType.IDENTIFIER);

    /** The one grammar {@code expected} fragment this reports, per {@code AtomTypeException}'s vocabulary. */
    private static final String EXPECTED = "an identifier";

    private final IdentifierType constraints;
    private final IdentifierProfile profile;
    private final TextParser text;

    public IdentifierParser(IdentifierType constraints) {
        this.constraints = constraints;
        this.profile = constraints.profile();
        this.text = new TextParser(constraints.textConstraints());
    }

    /** The constraint values this parser reads against. */
    public IdentifierType constraints() {
        return constraints;
    }

    @Override
    public String read(String text) {
        Optional<String> violation = profile.check(text);
        if (violation.isPresent()) {
            throw new AtomParseException(violation.get(), EXPECTED);
        }
        return this.text.read(text);
    }

    @Override
    public String write(String value) {
        return value;
    }

    /** This family already reads to text, so a string target is its own value and nothing else is. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return natural(String.class, target);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdentifierParser that && constraints.equals(that.constraints);
    }

    @Override
    public int hashCode() {
        return constraints.hashCode();
    }

    @Override
    public String toString() {
        return "IdentifierParser[" + constraints + "]";
    }
}
