package io.ltr8.tson.compiler.atom;

import java.math.BigInteger;
import java.util.Optional;

/**
 * The {@code precision} facet of the temporal atom families (§5.5), shared by {@link TimeParser} and
 * {@link DateTimeParser} because it is one rule stated once for both.
 *
 * <p><b>It is a constraint on the value, not on the spelling</b>, which meta.tn's own {@code @doc} for
 * {@code time_type} states and gives the example for: {@code precision: N} admits a value that is a whole
 * number of 10⁻ᴺ seconds, and "a text encoding may spell an admitted value with trailing zeros ({@code
 * 12:00:00.500} under {@code precision: 1}) and writes at most N digits". So the test is on the
 * fractional-second field of the parsed value and never on how many digits the author happened to type --
 * counting the token would refuse {@code 12:00:00.500} at {@code precision: 1}, which denotes exactly the
 * half-second {@code 12:00:00.5} does.
 *
 * <p>The atom is exact either way: nothing is truncated, and a value genuinely off the grid is rejected
 * rather than rounded onto it.
 */
final class FractionalSeconds {

    private FractionalSeconds() {
    }

    /** One second in nanoseconds -- the resolution {@code java.time} carries, and the finest {@code precision} can name. */
    private static final int NANOS_PER_SECOND_DIGITS = 9;

    /**
     * Refuses a fractional-second part finer than a nanosecond, by name.
     *
     * <p>RFC 3339's {@code time-secfrac} is {@code "." 1*DIGIT} with no upper bound, so the grammar admits a
     * value no host runtime has a type for. The cap is enforced either way -- {@code java.time}'s own parser
     * stops at nine digits -- but as a shape error carrying a message about a character index, which tells
     * an author their timestamp is malformed rather than that it is finer than the format carries. Named
     * here instead, once for the two families whose fraction reaches {@code java.time}, and matching what
     * {@code DurationParser} says for the same rule on the same production.
     *
     * @param text the token as written, already matched against its family's shape
     * @param atom the atom's name, for the diagnostic
     */
    static void checkRepresentable(String text, String atom) {
        int digits = fractionalDigits(text);
        if (digits > NANOS_PER_SECOND_DIGITS) {
            throw new AtomParseException("'" + text + "' states " + digits + " fractional-second digits, where a "
                    + atom + " carries at most " + NANOS_PER_SECOND_DIGITS + " -- one nanosecond is the finest "
                    + "resolution the value space admits (§5.5)",
                    "at most " + NANOS_PER_SECOND_DIGITS + " fractional-second digits");
        }
    }

    /** The digits between the fractional {@code .} and whatever follows them. */
    private static int fractionalDigits(String text) {
        int dot = text.lastIndexOf('.');
        if (dot < 0) {
            return 0;
        }
        int end = dot + 1;
        while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') {
            end++;
        }
        return end - dot - 1;
    }

    /**
     * Refuses {@code nanoOfSecond} when it is not a whole number of 10⁻ᴺ seconds.
     *
     * @param precision the facet, absent when the family leaves it unconstrained
     * @param nanoOfSecond the parsed value's own fractional second, in nanoseconds
     * @param text      the token as written, for the diagnostic
     * @param atom      the atom's name, for the diagnostic
     */
    static void check(Optional<BigInteger> precision, int nanoOfSecond, String text, String atom) {
        precision.ifPresent(allowed -> {
            // At nine digits and beyond the grid is finer than the value can be, so everything is on it.
            if (allowed.compareTo(BigInteger.valueOf(NANOS_PER_SECOND_DIGITS)) >= 0) {
                return;
            }
            int step = (int) Math.pow(10, NANOS_PER_SECOND_DIGITS - allowed.intValueExact());
            if (nanoOfSecond % step != 0) {
                throw new AtomValidationException("'" + text + "' is not a whole number of 10^-" + allowed
                        + " seconds, which is the resolution this " + atom + " admits",
                        "a whole number of 10^-" + allowed + " seconds");
            }
        });
    }
}
