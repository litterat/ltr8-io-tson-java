package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.RegexType;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses and validates against meta-kernel's {@code regex_type} constructor ({@code ~text_type &
 * atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc9485" }}) -- reuses {@link
 * TextParser}'s length/pattern constraint checks (applied to the regex's own source text, not what
 * it matches) via composition rather than duplicating them, and additionally requires that text to
 * actually compile as a regular expression. Holds a {@link RegexType} -- the pure constraint
 * values, unchanged by this split -- rather than declaring those fields itself. Not part of Part
 * 1's published built-in vocabulary (§5) and never registered in {@link BuiltinTypeVocabulary} --
 * this is groundwork for Part 2, which doesn't yet have anything that consumes a {@code regex}
 * value as a constraint.
 *
 * <p><b>Returns the regex's own source text ({@link String}), not a compiled {@code
 * java.util.regex.Pattern}.</b> {@code regex_type} composes with {@code text_type} (§5.7) -- a
 * {@code regex} value IS-A piece of text, the same relationship every other {@code AtomType} here
 * honors by returning its atom's own natural host representation (see {@code TextType.pattern()}'s
 * own Javadoc for why {@code text_type}/{@code uri_type}'s own {@code pattern} constraint field
 * moved to {@code String} for the identical reason). Compiling still happens here -- it's how this
 * atom validates the text actually is a well-formed regular expression -- the compiled {@link
 * Pattern} is just discarded once that check passes rather than becoming the return value.
 */
public record RegexParser(RegexType constraints) implements AtomType<String> {

    /** {@code regex => !regex_type {}} -- the unconstrained regex type. */
    public static final RegexParser UNCONSTRAINED = new RegexParser(RegexType.UNCONSTRAINED);

    @Override
    public String read(TokenValue token) {
        String text = new TextParser(constraints.constraints()).read(token);
        try {
            Pattern.compile(text);
        } catch (PatternSyntaxException e) {
            throw new AtomParseException("'" + text + "' is not a valid regular expression: " + e.getMessage());
        }
        return text;
    }

    @Override
    public String write(String value) {
        return value;
    }
}
