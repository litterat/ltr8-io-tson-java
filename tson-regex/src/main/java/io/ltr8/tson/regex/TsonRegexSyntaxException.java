package io.ltr8.tson.regex;

/**
 * Thrown when a string is not a valid I-Regexp (RFC 9485) -- either malformed, or using a construct outside
 * the interoperable subset (an anchor used as an assertion, {@code \d}/{@code \w}/{@code \s}, character-class
 * subtraction, a capture/back-reference, a Unicode block, lookaround, ...). Unchecked, like the rest of the
 * read/parse stack. {@link #position()} is the code-point index into the pattern where parsing failed.
 */
public final class TsonRegexSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String pattern;
    private final int position;

    public TsonRegexSyntaxException(String message, String pattern, int position) {
        super(message + " (at position " + position + " in \"" + pattern + "\")");
        this.pattern = pattern;
        this.position = position;
    }

    /** The pattern that failed to parse. */
    public String pattern() {
        return pattern;
    }

    /** The code-point index into {@link #pattern()} where parsing failed. */
    public int position() {
        return position;
    }
}
