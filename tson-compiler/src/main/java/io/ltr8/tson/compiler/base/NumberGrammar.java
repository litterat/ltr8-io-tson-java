package io.ltr8.tson.compiler.base;

import java.util.Optional;

/**
 * Recognizes the {@code number} production of §7.6 against a token's complete text (§4.3: "if and only if
 * its complete text matches the number production"). Pure identification: determines which of the four
 * grammar alternatives (if any) the text matches and extracts the grammar's own structural components as
 * raw substrings -- see {@link NumberForm}'s Javadoc for why it stops there rather than binding to a Java
 * numeric type.
 *
 * <p>The grammar is <b>hand-written</b>, one method per ABNF rule, in {@link NumberScanner}; this class is
 * the door and the full-text rule. That is a decision about what a reference implementation should contain:
 * a grammar stated as a {@code java.util.regex} pattern is stated in a dialect no other language shares, and
 * TSON pins I-Regexp for a schema's {@code pattern} facets while saying nothing about how a number is
 * recognized. It is also what a number cost -- nine anchored patterns tried in turn, a {@code Matcher} and
 * its internals allocated per attempt. {@code NumberScannerEquivalenceTest} keeps the patterns this replaced
 * as an oracle and fuzzes the two against each other.
 *
 * <p><b>Every entry point here requires the whole text</b>, per §4.3: a scan that stops short of the end is
 * no match, which is what makes {@code 3e} a string rather than a broken float.
 *
 * <p>{@link #isHexFloat}, {@link #tryRational}, and {@link #tryComplex} recognize §7.6's *extended* forms --
 * not part of {@code number}, each reachable only through its own built-in vocabulary atom ({@code
 * float32}/{@code float64}; {@code rational}; {@code complex}). Hex-float is a shape check with no
 * structural record ({@code Double.parseDouble} reads the text itself); rational and complex decompose into
 * {@link RationalForm}/{@link ComplexForm}.
 */
public final class NumberGrammar {

    private NumberGrammar() {
    }

    /** The two signed answers, held rather than rebuilt -- a sign is asked for on every number scanned. */
    private static final Optional<NumberForm.Sign> PLUS = Optional.of(NumberForm.Sign.PLUS);
    private static final Optional<NumberForm.Sign> MINUS = Optional.of(NumberForm.Sign.MINUS);

    /** The scanner reports an absent sign as {@code null}; {@link NumberForm} carries an {@link Optional}. */
    static Optional<NumberForm.Sign> optional(NumberForm.Sign sign) {
        if (sign == null) {
            return Optional.empty();
        }
        return sign == NumberForm.Sign.PLUS ? PLUS : MINUS;
    }

    /**
     * Attempts to match {@code text} against the {@code number} production in full. Returns empty if it
     * matches none of the four alternatives -- callers fall through to string, per §4.4 ("Any unquoted token
     * that does not match null, boolean, or the number production resolves to a string value... There are no
     * exceptions").
     */
    public static Optional<NumberForm> tryParse(String text) {
        NumberScanner scanner = new NumberScanner(text);
        NumberForm form = scanner.number();
        return form != null && scanner.atEnd() ? Optional.of(form) : Optional.empty();
    }

    /** See {@link NumberScanner#hexFloat}. Not tried by {@link #tryParse} -- an extended form, opt-in only. */
    public static boolean isHexFloat(String text) {
        NumberScanner scanner = new NumberScanner(text);
        return scanner.hexFloat() && scanner.atEnd();
    }

    /** See {@link NumberScanner#rational}. Not tried by {@link #tryParse} -- an extended form, opt-in only. */
    public static Optional<RationalForm> tryRational(String text) {
        NumberScanner scanner = new NumberScanner(text);
        RationalForm form = scanner.rational();
        return form != null && scanner.atEnd() ? Optional.of(form) : Optional.empty();
    }

    /** See {@link NumberScanner#complex}. Not tried by {@link #tryParse} -- an extended form, opt-in only. */
    public static Optional<ComplexForm> tryComplex(String text) {
        return Optional.ofNullable(new NumberScanner(text).complex());
    }
}
