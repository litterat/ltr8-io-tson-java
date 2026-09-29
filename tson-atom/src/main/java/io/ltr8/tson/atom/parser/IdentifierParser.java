package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.base.unicode.IdentifierProfile;
import io.ltr8.tson.schema.meta.IdentifierType;

import java.util.Optional;

/**
 * Parses and validates against meta-kernel's {@code identifier_type} constructor ({@code text_type & {}}) -- the
 * type of every naming position in the series: type names, field names and parameter names through the
 * {@code type_name}/{@code field_name}/{@code param_name} roles, and enum members through {@code enum_set}.
 * One contract, reaching all of them, which is the point of their sharing a type.
 *
 * <p><b>The grammar comes first, then the text facets.</b> A name failing §7.7 is refused as a grammar
 * violation whatever its facets say, so a refinement's {@code pattern} or {@code members} is only ever asked
 * of a well-formed name; the facets are {@link TextParser}'s, applied to the same text.
 *
 * <p><b>The profile itself is {@link IdentifierProfile}'s</b>, beside the UCD tables it reads. What is here
 * is only the atom: the rule that a name failing §7.7 is a <em>parse</em> failure, which is this position's
 * answer rather than the profile's. Every other caller of the profile -- the lexer's grammar, the schema
 * parser, the resolver, the linker -- owes a different one, which is why the check reports a violation and
 * each caller decides what it becomes.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5) -- like {@link TextParser} and {@link EnumParser},
 * never registered in {@link BuiltinTypeVocabulary} and has no {@code TYPENAME} constant.
 */
public record IdentifierParser(IdentifierType constraints) implements AtomTypeParser<String> {

    /** {@code identifier => !identifier_type {}} -- the unconstrained identifier type. */
    public static final IdentifierParser UNCONSTRAINED = new IdentifierParser(IdentifierType.UNCONSTRAINED);

    /** The one grammar {@code expected} fragment this reports, per {@code AtomTypeException}'s vocabulary. */
    private static final String EXPECTED = "an identifier";

    @Override
    public String read(String text) {
        Optional<String> violation = IdentifierProfile.validate(text);
        if (violation.isPresent()) {
            throw new AtomParseException(violation.get(), EXPECTED);
        }
        return new TextParser(constraints.textConstraints()).read(text);
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

}
