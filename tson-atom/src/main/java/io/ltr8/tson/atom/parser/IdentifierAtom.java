package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.base.unicode.IdentifierProfile;
import java.util.Optional;

/**
 * Meta-kernel's {@code identifier} instance of the {@code unit} atom constructor (§4.2, §8.1) -- the type of
 * every naming position in the series: type names, field names and parameter names through the
 * {@code type_name}/{@code field_name}/{@code param_name} roles, and enum members through {@code enum_set}.
 * One contract, reaching all of them, which is the point of their sharing a type.
 *
 * <p><b>The profile itself is {@link IdentifierProfile}'s</b>, beside the UCD tables it reads. What is here
 * is only the atom: the rule that a name failing §7.7 is a <em>parse</em> failure, which is this position's
 * answer rather than the profile's. Every other caller of the profile -- the lexer's grammar, the schema
 * parser, the resolver, the linker -- owes a different one, which is why the check reports a violation and
 * each caller decides what it becomes.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5) -- like {@link TextParser} and
 * {@link EnumParser}, never registered in {@link BuiltinTypeVocabulary} and has no {@code TYPENAME}
 * constant. {@code unit} is a Part 2 schema constructor, not a schemaless annotation a Class 1 processor
 * would ever resolve on its own. §4.2 requires an implementation to dispatch {@code value},
 * {@code identifier} and {@code void} by their declared names, all three resolving to the identical empty
 * body. This is the {@code identifier} branch; the other two belong to the encoding rather than to this
 * vocabulary -- {@code value} is read by base type resolution, which depends on the lexical form, and
 * {@code void} accepts only the absent sentinel.
 */
public final class IdentifierAtom implements AtomType<String> {

    public static final IdentifierAtom INSTANCE = new IdentifierAtom();

    /** The one {@code expected} fragment this reports -- a grammar, per {@code AtomTypeException}'s vocabulary. */
    private static final String EXPECTED = "an identifier";

    private IdentifierAtom() {
    }

    @Override
    public String read(String text) {
        Optional<String> violation = IdentifierProfile.validate(text);
        if (violation.isPresent()) {
            throw new AtomParseException(violation.get(), EXPECTED);
        }
        return text;
    }

    @Override
    public String write(String value) {
        return value;
    }
}
