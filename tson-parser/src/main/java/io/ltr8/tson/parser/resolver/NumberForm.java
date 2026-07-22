package io.ltr8.tson.parser.resolver;

import java.util.Optional;

/**
 * The recognized shape of a token matching the {@code number} production (§4.3, §7.6): which of
 * the four disjoint grammar alternatives it is, and that alternative's own components, captured
 * as raw substrings straight from the source text.
 *
 * <p>Deliberately not a Java numeric type. This is identification, not binding: digit groups here
 * are exactly as written (underscore separators included, based-integer digits not yet
 * interpreted in their radix, decimal digits not yet combined into a magnitude). Converting a
 * {@code NumberForm} into a {@code long}/{@code double}/{@code BigInteger}/{@code BigDecimal} --
 * and enforcing the equivalence the spec requires between different representations of the same
 * value ({@code 255}/{@code 0xFF}, {@code .5}/{@code 0.5}, {@code 1_000}/{@code 1000}, §4.3) -- is
 * a separate, later step, consuming this type rather than replacing it.
 */
public sealed interface NumberForm
        permits NumberForm.SpecialValueForm, NumberForm.IntegerForm, NumberForm.BasedIntegerForm, NumberForm.FloatForm {

    enum Sign { PLUS, MINUS }

    /**
     * {@code .nan}, {@code .inf}, {@code .infinity} (§4.3, §7.6). {@code sign} applies only to
     * infinity -- {@code special-value = [sign] infinity / ".nan"}, ABNF concatenation binding
     * tighter than alternation, so {@code .nan} is never signed; {@code +.nan}/{@code -.nan}
     * don't match this production at all.
     */
    record SpecialValueForm(Optional<Sign> sign, Kind kind) implements NumberForm {
        public enum Kind { NAN, INFINITY }
    }

    /** A signed decimal integer. {@code digits} has no leading zeros except the single digit {@code "0"}. */
    record IntegerForm(Optional<Sign> sign, String digits) implements NumberForm {}

    /** A {@code 0x}/{@code 0o}/{@code 0b}-prefixed integer. The prefix itself is lowercase-only by grammar. */
    record BasedIntegerForm(Optional<Sign> sign, Radix radix, String digits) implements NumberForm {
        public enum Radix { HEX, OCTAL, BINARY }
    }

    /**
     * A decimal float. Exactly one of {@code integerPart}/{@code fractionDigits} may be absent
     * (never both) per the grammar's three alternatives: {@code decimal-natural "." digits} has
     * both; {@code "." digits} has no integer part; {@code decimal-natural exponent} (mandatory
     * exponent, no dot) has no fraction.
     */
    record FloatForm(Optional<Sign> sign, Optional<String> integerPart, Optional<String> fractionDigits,
                      Optional<ExponentPart> exponent) implements NumberForm {}

    record ExponentPart(Optional<Sign> sign, String digits) {}
}
