package io.ltr8.tson.parser.resolver;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes the {@code number} production of §7.6 against a token's complete text (§4.3: "if
 * and only if its complete text matches the number production"). Pure identification: determines
 * which of the four grammar alternatives (if any) the text matches and extracts the grammar's own
 * structural components as raw substrings -- see {@link NumberForm}'s Javadoc for why it stops
 * there rather than binding to a Java numeric type.
 *
 * <p>Each production is matched with its own small, anchored (full-string) regex, transcribed
 * directly from the ABNF of §7.6 rather than combined into one pattern -- Java regex doesn't allow
 * a named group to repeat across alternation branches, and the three {@code float} alternatives
 * and three {@code based-integer} radixes each want their own named groups, so one pattern per
 * alternative reads far closer to the grammar than one combined pattern would.
 *
 * <p>{@link #isHexFloat} recognizes one of §7.6's *extended* forms (not part of {@code number},
 * reachable only through the built-in vocabulary's {@code float32}/{@code float64} atoms) -- see
 * its Javadoc for why it's a shape check only, with no corresponding {@code NumberForm} variant.
 */
public final class NumberGrammar {

    private NumberGrammar() {
    }

    private static final String DIGITS = "[0-9](?:_?[0-9])*";
    private static final String DECIMAL_NATURAL = "0|[1-9](?:_?[0-9])*";
    private static final String HEX_DIGITS = "[0-9A-Fa-f](?:_?[0-9A-Fa-f])*";
    private static final String OCTAL_DIGITS = "[0-7](?:_?[0-7])*";
    private static final String BINARY_DIGITS = "[01](?:_?[01])*";

    /** {@code exponent = ( "e" / "E" ) [sign] digits}. Embedded (not a top-level Pattern) so it always exists as named groups in whichever float pattern includes it. */
    private static final String EXPONENT = "[eE](?<expsign>[+-])?(?<expdigits>" + DIGITS + ")";

    private static final Pattern INTEGER =
            Pattern.compile("(?<sign>[+-])?(?<digits>" + DECIMAL_NATURAL + ")");

    private static final Pattern HEX = Pattern.compile("(?<sign>[+-])?0x(?<digits>" + HEX_DIGITS + ")");
    private static final Pattern OCTAL = Pattern.compile("(?<sign>[+-])?0o(?<digits>" + OCTAL_DIGITS + ")");
    private static final Pattern BINARY = Pattern.compile("(?<sign>[+-])?0b(?<digits>" + BINARY_DIGITS + ")");

    /** {@code decimal-natural "." digits [exponent]} -- e.g. {@code 1.5}, {@code 10.25e3}. */
    private static final Pattern FLOAT_DOT_WITH_INT = Pattern.compile(
            "(?<sign>[+-])?(?<intpart>" + DECIMAL_NATURAL + ")\\.(?<frac>" + DIGITS + ")(?:" + EXPONENT + ")?");
    /** {@code "." digits [exponent]} -- e.g. {@code .5}, {@code .5e-3}. */
    private static final Pattern FLOAT_DOT_NO_INT = Pattern.compile(
            "(?<sign>[+-])?\\.(?<frac>" + DIGITS + ")(?:" + EXPONENT + ")?");
    /** {@code decimal-natural exponent} -- e.g. {@code 1e10}. No dot; exponent is mandatory here. */
    private static final Pattern FLOAT_EXP_NO_DOT = Pattern.compile(
            "(?<sign>[+-])?(?<intpart>" + DECIMAL_NATURAL + ")(?:" + EXPONENT + ")");

    /** {@code [sign] infinity} where {@code infinity = ".inf" / ".infinity"}. */
    private static final Pattern INFINITY = Pattern.compile("(?<sign>[+-])?\\.(?:inf|infinity)");
    /** {@code ".nan"} -- never signed (see {@link NumberForm.SpecialValueForm}). */
    private static final Pattern NAN = Pattern.compile("\\.nan");

    /**
     * {@code hex-float = [sign] "0x" hex-digits [ "." hex-digits ] hex-exponent
     *              / [sign] "0x" "." hex-digits hex-exponent}
     * -- an extended form (§7.6: "recognised only through the numeric atoms of the type vocabulary,
     * §5.6"), never part of the base {@code number} production {@link #tryParse} recognizes, only
     * reachable through {@code float32}/{@code float64}. Unlike every other form here, this isn't
     * decomposed into a structural record: {@code float32}/{@code float64} are approximate anyway
     * (no representation-equivalence requirement to preserve, unlike the integer family's {@code
     * 0xFF}/{@code 255}), and this exact syntax is also Java's own hexadecimal floating-point
     * literal grammar (JLS §3.10.2, minus the optional type suffix TSON's grammar doesn't have) --
     * {@link Double#parseDouble}/{@link Float#parseFloat} parse it directly and correctly, so this
     * method exists only to confirm the text matches TSON's grammar *before* handing it to them
     * (whose accepted syntax isn't necessarily identical in every corner case).
     */
    private static final Pattern HEX_FLOAT_WITH_INT =
            Pattern.compile("[+-]?0x" + HEX_DIGITS + "(?:\\." + HEX_DIGITS + ")?[pP][+-]?" + DIGITS);
    private static final Pattern HEX_FLOAT_NO_INT =
            Pattern.compile("[+-]?0x\\." + HEX_DIGITS + "[pP][+-]?" + DIGITS);

    /**
     * Attempts to match {@code text} against the {@code number} production in full. Returns
     * empty if it matches none of the four alternatives -- callers fall through to string, per
     * §4.4 ("Any unquoted token that does not match null, boolean, or the number production
     * resolves to a string value... There are no exceptions").
     */
    public static Optional<NumberForm> tryParse(String text) {
        Optional<NumberForm> special = trySpecialValue(text);
        if (special.isPresent()) {
            return special;
        }
        Optional<NumberForm> based = tryBasedInteger(text);
        if (based.isPresent()) {
            return based;
        }
        Optional<NumberForm> flt = tryFloat(text);
        if (flt.isPresent()) {
            return flt;
        }
        return tryInteger(text);
    }

    private static Optional<NumberForm> trySpecialValue(String text) {
        if (NAN.matcher(text).matches()) {
            return Optional.of(new NumberForm.SpecialValueForm(Optional.empty(), NumberForm.SpecialValueForm.Kind.NAN));
        }
        Matcher inf = INFINITY.matcher(text);
        if (inf.matches()) {
            return Optional.of(new NumberForm.SpecialValueForm(sign(inf), NumberForm.SpecialValueForm.Kind.INFINITY));
        }
        return Optional.empty();
    }

    private static Optional<NumberForm> tryBasedInteger(String text) {
        Matcher hex = HEX.matcher(text);
        if (hex.matches()) {
            return Optional.of(new NumberForm.BasedIntegerForm(sign(hex), NumberForm.BasedIntegerForm.Radix.HEX, hex.group("digits")));
        }
        Matcher octal = OCTAL.matcher(text);
        if (octal.matches()) {
            return Optional.of(new NumberForm.BasedIntegerForm(sign(octal), NumberForm.BasedIntegerForm.Radix.OCTAL, octal.group("digits")));
        }
        Matcher binary = BINARY.matcher(text);
        if (binary.matches()) {
            return Optional.of(new NumberForm.BasedIntegerForm(sign(binary), NumberForm.BasedIntegerForm.Radix.BINARY, binary.group("digits")));
        }
        return Optional.empty();
    }

    private static Optional<NumberForm> tryFloat(String text) {
        Matcher withInt = FLOAT_DOT_WITH_INT.matcher(text);
        if (withInt.matches()) {
            return Optional.of(new NumberForm.FloatForm(
                    sign(withInt), Optional.of(withInt.group("intpart")), Optional.of(withInt.group("frac")), exponent(withInt)));
        }
        Matcher noInt = FLOAT_DOT_NO_INT.matcher(text);
        if (noInt.matches()) {
            return Optional.of(new NumberForm.FloatForm(
                    sign(noInt), Optional.empty(), Optional.of(noInt.group("frac")), exponent(noInt)));
        }
        Matcher expNoDot = FLOAT_EXP_NO_DOT.matcher(text);
        if (expNoDot.matches()) {
            return Optional.of(new NumberForm.FloatForm(
                    sign(expNoDot), Optional.of(expNoDot.group("intpart")), Optional.empty(), exponent(expNoDot)));
        }
        return Optional.empty();
    }

    /** See {@link #HEX_FLOAT_WITH_INT}'s Javadoc. Not tried by {@link #tryParse} -- an extended form, opt-in only. */
    public static boolean isHexFloat(String text) {
        return HEX_FLOAT_WITH_INT.matcher(text).matches() || HEX_FLOAT_NO_INT.matcher(text).matches();
    }

    private static Optional<NumberForm> tryInteger(String text) {
        Matcher m = INTEGER.matcher(text);
        if (m.matches()) {
            return Optional.of(new NumberForm.IntegerForm(sign(m), m.group("digits")));
        }
        return Optional.empty();
    }

    private static Optional<NumberForm.Sign> sign(Matcher m) {
        return toSign(m.group("sign"));
    }

    private static Optional<NumberForm.Sign> toSign(String s) {
        if (s == null) {
            return Optional.empty();
        }
        return Optional.of(s.equals("+") ? NumberForm.Sign.PLUS : NumberForm.Sign.MINUS);
    }

    /** Only called on matchers from the three float patterns, which all embed {@link #EXPONENT}, so the named groups always exist. */
    private static Optional<NumberForm.ExponentPart> exponent(Matcher m) {
        String digits = m.group("expdigits");
        if (digits == null) {
            return Optional.empty();
        }
        return Optional.of(new NumberForm.ExponentPart(toSign(m.group("expsign")), digits));
    }
}
