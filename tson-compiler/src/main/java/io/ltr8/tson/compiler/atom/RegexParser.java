package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.regex.TsonRegex;
import io.ltr8.tson.regex.TsonRegexSyntaxException;
import io.ltr8.tson.schema.meta.RegexType;

/**
 * Parses and validates against meta-kernel's {@code regex_type} constructor ({@code ~text_type &
 * atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc9485" }}) -- reuses {@link
 * TextParser}'s length/pattern constraint checks (applied to the regex's own source text, not what it
 * matches) via composition rather than duplicating them, and additionally requires that text to be a valid
 * I-Regexp. Holds a {@link RegexType} -- the pure constraint values, unchanged by this split -- rather than
 * declaring those fields itself. Not part of Part 1's published built-in vocabulary (§5) and never
 * registered in {@link BuiltinTypeVocabulary}.
 *
 * <p><b>Returns the regex's own source text ({@link String}), not a parsed matcher.</b> {@code regex_type}
 * composes with {@code text_type} (§5.7) -- a {@code regex} value IS-A piece of text, the same relationship
 * every other {@code AtomType} here honors by returning its atom's own natural host representation (see
 * {@code TextType.pattern()}'s own Javadoc for why {@code text_type}/{@code uri_type}'s own {@code pattern}
 * constraint field is {@code String} for the identical reason).
 *
 * <p><b>Validation goes through {@code tson-regex}, not {@code java.util.regex}.</b> {@code regex_type}'s
 * {@code spec} is pinned {@code REQUIRED_FIXED} to RFC 9485 (I-Regexp), so the text is validated against the
 * I-Regexp subset via {@link TsonRegex#parse} -- which rejects the non-interoperable constructs the JVM's
 * engine would silently accept ({@code \d}/{@code \w}/{@code \s}, subtraction, back-references, lookaround,
 * Unicode blocks). The parsed form is discarded once validation passes; matching a value against a {@code
 * pattern} is a separate capability built on {@code tson-regex}'s AST (see {@code BACKLOG.md}).
 */
public record RegexParser(RegexType constraints) implements AtomType<String> {

    /** {@code regex => !regex_type {}} -- the unconstrained regex type. */
    public static final RegexParser UNCONSTRAINED = new RegexParser(RegexType.UNCONSTRAINED);

    @Override
    public String read(TokenValue token) {
        String text = new TextParser(constraints.constraints()).read(token);
        try {
            TsonRegex.parse(text);
        } catch (TsonRegexSyntaxException e) {
            throw new AtomParseException("'" + text + "' is not a valid I-Regexp (RFC 9485): " + e.getMessage());
        }
        return text;
    }

    @Override
    public String write(String value) {
        return value;
    }
}
