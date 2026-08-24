package io.ltr8.tson.compiler.base;

import java.util.Optional;

/**
 * A cursor over a token's text with one method per production of §7.6's numeric ABNF -- the scanner
 * {@link NumberGrammar} recognizes numbers with.
 *
 * <p><b>Why hand-written rather than a regex.</b> This is the reference implementation, and a grammar stated
 * as a {@code java.util.regex} pattern with named groups is stated in a dialect no other language shares --
 * an unspecified host dependency in the one artifact that should carry the spec's own algorithm. TSON pins
 * I-Regexp for a schema's {@code pattern} facets and says nothing about how a number is recognized, so the
 * regex here was this implementation's private choice leaking into what others copy. Each method below reads
 * as its ABNF rule, and ports as one. It is also what a number costs: matching a token against nine anchored
 * patterns in turn allocated a {@code Matcher} and its internals per attempt -- 47 matchers per read of a
 * document holding seven numbers, about a fifth of everything a read allocated.
 *
 * <p><b>The cursor is single-pass with explicit backtracking</b> ({@link #mark()}/{@link #reset(int)}) at the
 * two places the grammar is genuinely optional -- a float's fraction and its exponent -- because a regex
 * backtracks there and the results must agree. Everywhere else the grammar is decided by the character at
 * the cursor, so no lookahead is needed: a digit run is maximal-munch and nothing that may follow one is a
 * digit. {@code NumberScannerEquivalenceTest} holds the old patterns as an oracle and fuzzes both.
 *
 * <p>Not thread-safe, single-use, and package-private: {@link NumberGrammar} is the door.
 */
final class NumberScanner {

    private final String text;
    private int at;

    NumberScanner(String text) {
        this.text = text;
    }

    // ── Cursor primitives ───────────────────────────────────────────────

    boolean atEnd() {
        return at >= text.length();
    }

    int mark() {
        return at;
    }

    void reset(int mark) {
        this.at = mark;
    }

    /** Consumes {@code c} if it is the character at the cursor. */
    private boolean take(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    /** Consumes {@code word} if the cursor is on it. */
    private boolean take(String word) {
        if (text.startsWith(word, at)) {
            at += word.length();
            return true;
        }
        return false;
    }

    private boolean peekIs(char c) {
        return at < text.length() && text.charAt(at) == c;
    }

    // ── Digits (§7.6) ───────────────────────────────────────────────────

    /** The four digit classes the grammar names, by radix. */
    private static boolean isDigit(char c, int radix) {
        return switch (radix) {
            case 10 -> c >= '0' && c <= '9';
            case 16 -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            case 8 -> c >= '0' && c <= '7';
            case 2 -> c == '0' || c == '1';
            default -> false;
        };
    }

    /**
     * {@code digits = digit *( ["_"] digit )} in the given radix -- an underscore is a separator
     * <em>between</em> digits, so it is consumed only with the digit that must follow it. Null when the
     * cursor is not on a digit; the cursor does not move in that case.
     */
    String digits(int radix) {
        int start = at;
        if (atEnd() || !isDigit(text.charAt(at), radix)) {
            return null;
        }
        at++;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (isDigit(c, radix)) {
                at++;
            } else if (c == '_' && at + 1 < text.length() && isDigit(text.charAt(at + 1), radix)) {
                at += 2;
            } else {
                break;
            }
        }
        return text.substring(start, at);
    }

    /** {@code decimal-natural = "0" / nonzero-digit *( ["_"] digit )} -- no leading zeros, and bare {@code 0} is one. */
    String decimalNatural() {
        if (atEnd()) {
            return null;
        }
        char c = text.charAt(at);
        if (c == '0') {
            at++;
            return "0";
        }
        if (c < '1' || c > '9') {
            return null;
        }
        return digits(10);
    }

    /** {@code denominator = nonzero-digit *( ["_"] digit )} -- a natural, but never {@code 0} and never zero-led. */
    String nonZeroNatural() {
        if (atEnd()) {
            return null;
        }
        char c = text.charAt(at);
        if (c < '1' || c > '9') {
            return null;
        }
        return digits(10);
    }

    // ── Sign and exponent ───────────────────────────────────────────────

    /** {@code sign = "+" / "-"}, optional at every position the grammar admits one but the middle of a complex. */
    NumberForm.Sign sign() {
        if (take('+')) {
            return NumberForm.Sign.PLUS;
        }
        if (take('-')) {
            return NumberForm.Sign.MINUS;
        }
        return null;
    }

    /**
     * {@code exponent = ("e" / "E") [sign] digits} -- null, <b>with the cursor put back</b>, when what
     * follows the {@code e} is not one. The grammar's optional-group backtracking, made explicit: {@code
     * 3e} is an integer followed by junk, not a float with a broken exponent, and {@code 4ei} in a complex
     * is magnitude {@code 4} followed by the letter that is not there.
     */
    NumberForm.ExponentPart exponent() {
        int mark = mark();
        if (!take('e') && !take('E')) {
            return null;
        }
        NumberForm.Sign sign = sign();
        String digits = digits(10);
        if (digits == null) {
            reset(mark);
            return null;
        }
        return new NumberForm.ExponentPart(NumberGrammar.optional(sign), digits);
    }

    // ── The four alternatives of `number` (§7.6) ────────────────────────

    /**
     * One whole {@code number}, or null. The alternatives are disjoint on the character after the optional
     * sign, so this is a dispatch rather than a sequence of attempts: {@code .} opens a special value or a
     * fraction-only float, {@code 0x}/{@code 0o}/{@code 0b} a based integer, and a decimal digit an integer
     * or a float depending on what follows it.
     */
    NumberForm number() {
        NumberForm.Sign sign = sign();

        if (peekIs('.')) {
            return dotLeading(sign);
        }
        NumberForm based = basedInteger(sign);
        if (based != null) {
            return based;
        }

        String integerPart = decimalNatural();
        if (integerPart == null) {
            return null;
        }
        if (take('.')) {
            String fraction = digits(10);
            if (fraction == null) {
                return null;
            }
            return new NumberForm.FloatForm(NumberGrammar.optional(sign), Optional.of(integerPart),
                    Optional.of(fraction), Optional.ofNullable(exponent()));
        }
        NumberForm.ExponentPart exponent = exponent();
        if (exponent != null) {
            return new NumberForm.FloatForm(NumberGrammar.optional(sign), Optional.of(integerPart),
                    Optional.empty(), Optional.of(exponent));
        }
        return new NumberForm.IntegerForm(NumberGrammar.optional(sign), integerPart);
    }

    /**
     * What a leading {@code .} can open: {@code .nan}, {@code .inf}, {@code .infinity}, or {@code "." digits
     * [exponent]}. <b>{@code .infinity} is tried before {@code .inf}</b> -- the ABNF's alternation is
     * unordered and a regex backtracks into the longer one, so a scanner has to prefer it explicitly.
     * <b>{@code .nan} is never signed</b>: {@code special-value = [sign] infinity / ".nan"}, concatenation
     * binding tighter than alternation, so {@code +.nan} is not a number at all.
     */
    private NumberForm dotLeading(NumberForm.Sign sign) {
        if (sign == null && take(".nan")) {
            return new NumberForm.SpecialValueForm(Optional.empty(),
                    NumberForm.SpecialValueForm.Kind.NAN);
        }
        if (take(".infinity") || take(".inf")) {
            return new NumberForm.SpecialValueForm(NumberGrammar.optional(sign),
                    NumberForm.SpecialValueForm.Kind.INFINITY);
        }
        if (!take('.')) {
            return null;
        }
        String fraction = digits(10);
        if (fraction == null) {
            return null;
        }
        return new NumberForm.FloatForm(NumberGrammar.optional(sign), Optional.empty(),
                Optional.of(fraction), Optional.ofNullable(exponent()));
    }

    /**
     * {@code based-integer = [sign] ( "0x" hex-digits / "0o" octal-digits / "0b" binary-digits )}.
     *
     * <p><b>A consumed prefix is put back when its digits do not follow</b>, so the decimal alternative sees
     * the {@code 0} it starts with: without that, {@code 0o9} scans as the integer {@code 9} with the prefix
     * silently eaten, and a token that is not a number at all becomes one.
     */
    private NumberForm basedInteger(NumberForm.Sign sign) {
        int mark = mark();
        int radix = radixPrefix();
        if (radix == 0) {
            return null;
        }
        String digits = digits(radix);
        if (digits == null) {
            reset(mark);
            return null;
        }
        NumberForm.BasedIntegerForm.Radix named = switch (radix) {
            case 16 -> NumberForm.BasedIntegerForm.Radix.HEX;
            case 8 -> NumberForm.BasedIntegerForm.Radix.OCTAL;
            default -> NumberForm.BasedIntegerForm.Radix.BINARY;
        };
        return new NumberForm.BasedIntegerForm(NumberGrammar.optional(sign), named, digits);
    }

    /** The radix a consumed {@code 0x}/{@code 0o}/{@code 0b} prefix names, or {@code 0} for no prefix. The prefix letters are lowercase by grammar. */
    private int radixPrefix() {
        if (take("0x")) {
            return 16;
        }
        if (take("0o")) {
            return 8;
        }
        if (take("0b")) {
            return 2;
        }
        return 0;
    }

    // ── The extended forms (§7.6), each reachable only through its own atom ──

    /** {@code hex-float}, a shape check with nothing to extract -- {@code Double.parseDouble} reads the text itself. */
    boolean hexFloat() {
        sign();
        if (radixPrefix() != 16) {
            return false;
        }
        if (take('.')) {
            if (digits(16) == null) {
                return false;
            }
        } else {
            if (digits(16) == null) {
                return false;
            }
            if (take('.') && digits(16) == null) {
                return false;
            }
        }
        if (!take('p') && !take('P')) {
            return false;
        }
        sign();
        return digits(10) != null;
    }

    /** {@code rational = [sign] decimal-natural "/" denominator}. */
    RationalForm rational() {
        NumberForm.Sign sign = sign();
        String numerator = decimalNatural();
        if (numerator == null || !take('/')) {
            return null;
        }
        String denominator = nonZeroNatural();
        if (denominator == null) {
            return null;
        }
        return new RationalForm(NumberGrammar.optional(sign), numerator, denominator);
    }

    /**
     * {@code magnitude = decimal-natural [ "." digits ] [ exponent ] / "." digits [ exponent ]} -- unsigned,
     * returned as the raw substring, since {@link NumberGrammar#tryComplex} decomposes a part by running
     * {@link #number()} over it rather than duplicating digit extraction.
     */
    String magnitude() {
        int start = at;
        if (take('.')) {
            if (digits(10) == null) {
                reset(start);
                return null;
            }
            exponent();
            return text.substring(start, at);
        }
        if (decimalNatural() == null) {
            reset(start);
            return null;
        }
        int beforeFraction = mark();
        if (take('.') && digits(10) == null) {
            reset(beforeFraction);
        }
        exponent();
        return text.substring(start, at);
    }

    /**
     * {@code complex = [sign] magnitude sign magnitude imag-unit / [sign] magnitude imag-unit} -- the
     * two-part form first, since a purely imaginary {@code 1e+5i} is only the second form once the first
     * has failed on the sign its magnitude swallowed.
     */
    ComplexForm complex() {
        int start = mark();
        ComplexForm twoPart = complexTwoPart();
        if (twoPart != null) {
            return twoPart;
        }
        reset(start);

        NumberForm.Sign sign = sign();
        String magnitude = magnitude();
        if (magnitude == null || !imaginaryUnit() || !atEnd()) {
            return null;
        }
        return new ComplexForm(Optional.empty(), Optional.empty(),
                NumberGrammar.optional(sign), magnitude);
    }

    private ComplexForm complexTwoPart() {
        NumberForm.Sign realSign = sign();
        String real = magnitude();
        if (real == null) {
            return null;
        }
        NumberForm.Sign imaginarySign = sign();
        if (imaginarySign == null) {
            return null;   // the middle sign is mandatory, unlike every other sign in this grammar
        }
        String imaginary = magnitude();
        if (imaginary == null || !imaginaryUnit() || !atEnd()) {
            return null;
        }
        return new ComplexForm(NumberGrammar.optional(realSign), Optional.of(real),
                Optional.of(imaginarySign), imaginary);
    }

    /** {@code imag-unit = "i" / "j"}. */
    private boolean imaginaryUnit() {
        return take('i') || take('j');
    }
}
