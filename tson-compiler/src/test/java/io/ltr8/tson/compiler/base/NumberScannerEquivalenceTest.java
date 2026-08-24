package io.ltr8.tson.compiler.base;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hand-written {@link NumberScanner} against the regexes it replaced, held here as an oracle.
 *
 * <p>Replacing a grammar implementation is exactly the change unit tests are worst at: they cover the cases
 * someone thought of, and a scanner's mistakes live in the cases nobody did -- a backtracking corner, a
 * longest-alternative tie, an underscore at an edge. So the patterns are kept verbatim below (this is their
 * only remaining copy) and both implementations are run over the same inputs: every string up to length four
 * over an alphabet chosen to be all significant characters of the numeric grammar, then a fuzz over longer
 * ones. Equality is over the whole {@link NumberForm}, not just match/no-match, so a difference in an
 * extracted digit group fails too.
 *
 * <p>If a future change to the grammar makes these disagree <em>deliberately</em>, the oracle is what has to
 * be updated first, and the diff will say what changed.
 */
class NumberScannerEquivalenceTest {

    // ── The patterns this replaced, verbatim ────────────────────────────

    private static final String DIGITS = "[0-9](?:_?[0-9])*";
    private static final String DECIMAL_NATURAL = "0|[1-9](?:_?[0-9])*";
    private static final String HEX_DIGITS = "[0-9A-Fa-f](?:_?[0-9A-Fa-f])*";
    private static final String OCTAL_DIGITS = "[0-7](?:_?[0-7])*";
    private static final String BINARY_DIGITS = "[01](?:_?[01])*";
    private static final String EXPONENT = "[eE](?<expsign>[+-])?(?<expdigits>" + DIGITS + ")";

    private static final Pattern INTEGER = Pattern.compile("(?<sign>[+-])?(?<digits>" + DECIMAL_NATURAL + ")");
    private static final Pattern HEX = Pattern.compile("(?<sign>[+-])?0x(?<digits>" + HEX_DIGITS + ")");
    private static final Pattern OCTAL = Pattern.compile("(?<sign>[+-])?0o(?<digits>" + OCTAL_DIGITS + ")");
    private static final Pattern BINARY = Pattern.compile("(?<sign>[+-])?0b(?<digits>" + BINARY_DIGITS + ")");
    private static final Pattern FLOAT_DOT_WITH_INT = Pattern.compile(
            "(?<sign>[+-])?(?<intpart>" + DECIMAL_NATURAL + ")\\.(?<frac>" + DIGITS + ")(?:" + EXPONENT + ")?");
    private static final Pattern FLOAT_DOT_NO_INT = Pattern.compile(
            "(?<sign>[+-])?\\.(?<frac>" + DIGITS + ")(?:" + EXPONENT + ")?");
    private static final Pattern FLOAT_EXP_NO_DOT = Pattern.compile(
            "(?<sign>[+-])?(?<intpart>" + DECIMAL_NATURAL + ")(?:" + EXPONENT + ")");
    private static final Pattern INFINITY = Pattern.compile("(?<sign>[+-])?\\.(?:inf|infinity)");
    private static final Pattern NAN = Pattern.compile("\\.nan");
    private static final Pattern HEX_FLOAT_WITH_INT =
            Pattern.compile("[+-]?0x" + HEX_DIGITS + "(?:\\." + HEX_DIGITS + ")?[pP][+-]?" + DIGITS);
    private static final Pattern HEX_FLOAT_NO_INT =
            Pattern.compile("[+-]?0x\\." + HEX_DIGITS + "[pP][+-]?" + DIGITS);
    private static final Pattern RATIONAL = Pattern.compile(
            "(?<sign>[+-])?(?<num>" + DECIMAL_NATURAL + ")/(?<den>[1-9](?:_?[0-9])*)");
    /**
     * <b>One character differs from the original, and it is a bug fix.</b> {@code DECIMAL_NATURAL} is
     * {@code "0|[1-9](?:_?[0-9])*"} -- an unparenthesized alternation -- and every other pattern here wraps
     * it in a named group, which parenthesizes it. This one deliberately has no group (a named group cannot
     * repeat, and a magnitude appears twice), so concatenating it spliced a bare {@code |} into a larger
     * alternation and the {@code 0} branch terminated the whole first alternative. The effect: a complex
     * value whose magnitude is zero-led with anything after it -- {@code 0.5i}, {@code 0e3i}, {@code 0.5-1i}
     * -- was refused, while {@code 1.5i} worked, which is why it went unnoticed. The scanner reads the ABNF,
     * so it accepts them; the oracle is wrapped in {@code (?:...)} here to agree, and
     * {@link NumberGrammarTest} carries the regression test.
     */
    private static final String MAGNITUDE =
            "(?:(?:" + DECIMAL_NATURAL + ")(?:\\." + DIGITS + ")?(?:[eE][+-]?" + DIGITS + ")?"
            + "|\\." + DIGITS + "(?:[eE][+-]?" + DIGITS + ")?)";
    private static final Pattern COMPLEX_TWO_PART = Pattern.compile(
            "(?<sign1>[+-])?(?<mag1>" + MAGNITUDE + ")(?<sign2>[+-])(?<mag2>" + MAGNITUDE + ")(?<unit>[ij])");
    private static final Pattern COMPLEX_IMAGINARY_ONLY = Pattern.compile(
            "(?<sign1>[+-])?(?<mag1>" + MAGNITUDE + ")(?<unit>[ij])");

    // ── The oracle: the old NumberGrammar, unchanged ────────────────────

    private static Optional<NumberForm> oracleParse(String text) {
        if (NAN.matcher(text).matches()) {
            return Optional.of(new NumberForm.SpecialValueForm(Optional.empty(),
                    NumberForm.SpecialValueForm.Kind.NAN));
        }
        Matcher inf = INFINITY.matcher(text);
        if (inf.matches()) {
            return Optional.of(new NumberForm.SpecialValueForm(sign(inf),
                    NumberForm.SpecialValueForm.Kind.INFINITY));
        }
        for (var radix : List.of(Map.entry(HEX, NumberForm.BasedIntegerForm.Radix.HEX),
                Map.entry(OCTAL, NumberForm.BasedIntegerForm.Radix.OCTAL),
                Map.entry(BINARY, NumberForm.BasedIntegerForm.Radix.BINARY))) {
            Matcher m = radix.getKey().matcher(text);
            if (m.matches()) {
                return Optional.of(new NumberForm.BasedIntegerForm(sign(m), radix.getValue(), m.group("digits")));
            }
        }
        Matcher withInt = FLOAT_DOT_WITH_INT.matcher(text);
        if (withInt.matches()) {
            return Optional.of(new NumberForm.FloatForm(sign(withInt), Optional.of(withInt.group("intpart")),
                    Optional.of(withInt.group("frac")), exponent(withInt)));
        }
        Matcher noInt = FLOAT_DOT_NO_INT.matcher(text);
        if (noInt.matches()) {
            return Optional.of(new NumberForm.FloatForm(sign(noInt), Optional.empty(),
                    Optional.of(noInt.group("frac")), exponent(noInt)));
        }
        Matcher expNoDot = FLOAT_EXP_NO_DOT.matcher(text);
        if (expNoDot.matches()) {
            return Optional.of(new NumberForm.FloatForm(sign(expNoDot), Optional.of(expNoDot.group("intpart")),
                    Optional.empty(), exponent(expNoDot)));
        }
        Matcher integer = INTEGER.matcher(text);
        if (integer.matches()) {
            return Optional.of(new NumberForm.IntegerForm(sign(integer), integer.group("digits")));
        }
        return Optional.empty();
    }

    private static boolean oracleHexFloat(String text) {
        return HEX_FLOAT_WITH_INT.matcher(text).matches() || HEX_FLOAT_NO_INT.matcher(text).matches();
    }

    private static Optional<RationalForm> oracleRational(String text) {
        Matcher m = RATIONAL.matcher(text);
        return m.matches()
                ? Optional.of(new RationalForm(sign(m), m.group("num"), m.group("den")))
                : Optional.empty();
    }

    private static Optional<ComplexForm> oracleComplex(String text) {
        Matcher two = COMPLEX_TWO_PART.matcher(text);
        if (two.matches()) {
            return Optional.of(new ComplexForm(toSign(two.group("sign1")), Optional.of(two.group("mag1")),
                    toSign(two.group("sign2")), two.group("mag2")));
        }
        Matcher only = COMPLEX_IMAGINARY_ONLY.matcher(text);
        if (only.matches()) {
            return Optional.of(new ComplexForm(Optional.empty(), Optional.empty(),
                    toSign(only.group("sign1")), only.group("mag1")));
        }
        return Optional.empty();
    }

    private static Optional<NumberForm.Sign> sign(Matcher m) {
        return toSign(m.group("sign") != null ? m.group("sign") : null);
    }

    private static Optional<NumberForm.Sign> toSign(String s) {
        return s == null ? Optional.empty()
                : Optional.of(s.equals("+") ? NumberForm.Sign.PLUS : NumberForm.Sign.MINUS);
    }

    private static Optional<NumberForm.ExponentPart> exponent(Matcher m) {
        String digits = m.group("expdigits");
        return digits == null ? Optional.empty()
                : Optional.of(new NumberForm.ExponentPart(toSign(m.group("expsign")), digits));
    }

    // ── The comparison ──────────────────────────────────────────────────

    /** Every significant character of §7.6's grammar, plus one that is significant nowhere. */
    private static final char[] ALPHABET =
            {'0', '1', '7', '9', '_', '.', 'x', 'o', 'b', 'e', 'E', '+', '-', 'i', 'n', 'f', 'a', '/', 'p', 'q'};

    private static void assertAgrees(String text) {
        assertEquals(oracleParse(text), NumberGrammar.tryParse(text), () -> "tryParse(\"" + text + "\")");
        assertEquals(oracleHexFloat(text), NumberGrammar.isHexFloat(text), () -> "isHexFloat(\"" + text + "\")");
        assertEquals(oracleRational(text), NumberGrammar.tryRational(text), () -> "tryRational(\"" + text + "\")");
        assertEquals(oracleComplex(text), NumberGrammar.tryComplex(text), () -> "tryComplex(\"" + text + "\")");
    }

    /** Exhaustive to length three: 8,420 strings, every one of them a case someone would have to think of. */
    @Test
    void agreesOnEveryShortStringOverTheGrammarsOwnAlphabet() {
        List<String> all = new ArrayList<>(List.of(""));
        List<String> previous = List.of("");
        for (int length = 1; length <= 3; length++) {
            List<String> next = new ArrayList<>();
            for (String prefix : previous) {
                for (char c : ALPHABET) {
                    next.add(prefix + c);
                }
            }
            all.addAll(next);
            previous = next;
        }
        for (String text : all) {
            assertAgrees(text);
        }
    }

    /** Longer strings, where the backtracking corners live -- seeded, so a failure is reproducible. */
    @Test
    void agreesOnFuzzedLongerStrings() {
        Random random = new Random(20260824L);
        for (int i = 0; i < 120_000; i++) {
            int length = 4 + random.nextInt(10);
            StringBuilder text = new StringBuilder(length);
            for (int c = 0; c < length; c++) {
                text.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            assertAgrees(text.toString());
        }
    }

    /** Shapes a random walk reaches rarely: real numbers, and near-misses one character away from one. */
    @Test
    void agreesOnRealNumbersAndTheirNearMisses() {
        List<String> seeds = List.of(
                "0", "-0", "+0", "255", "1_000", "0xFF", "0Xff", "0o777", "0b1010", "-0x1_F",
                ".5", "0.5", "1.", "1.5e3", "1.5E+3", "1e10", "1e+10", "1e-10", "3e", "1_0.2_5e1_0",
                ".inf", "-.inf", ".infinity", "+.infinity", ".nan", "+.nan", "-.nan", ".INF", ".Inf",
                "0x1.8p3", "0x.8p3", "-0x1p-2", "0x1p", "1/2", "-3/4", "1/0", "0/1", "1/01", "1/",
                "3+4i", "3-4i", "-3+4i", "4i", "-4j", "1e+5i", "1e+5+2i", "3.5-2e1j", "3++4i", "3+4",
                "01", "0_1", "1__2", "_1", "1_", "1._5", "1.5_", "00", "0x", "0o8", "0b2", "0xG");
        for (String seed : seeds) {
            assertAgrees(seed);
            for (char c : ALPHABET) {
                assertAgrees(seed + c);
                assertAgrees(c + seed);
            }
        }
    }
}
