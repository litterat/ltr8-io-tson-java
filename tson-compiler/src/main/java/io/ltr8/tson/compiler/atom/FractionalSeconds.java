package io.ltr8.tson.compiler.atom;

import java.math.BigInteger;
import java.util.Optional;

/**
 * The {@code precision} facet of the temporal atom families (§5.5), shared by {@link TimeParser} and
 * {@link DateTimeParser} because it is one rule stated once for both: a token's fractional-second part
 * may carry <em>at most</em> {@code precision} digits, and {@code precision: 0} admits no fractional part
 * at all.
 *
 * <p>The count is taken from the token <em>as written</em>, never from the parsed value: {@code
 * 12:00:00.100} carries three digits whatever instant it denotes, and the temporal atoms are exact, so
 * the facet is a validation constraint and never an instruction to truncate. Callers reach it after the
 * shape regex has matched, so the text is known to be well-formed and the fraction -- if there is one --
 * is the run of digits after the last {@code .}.
 */
final class FractionalSeconds {

    private FractionalSeconds() {
    }

    /**
     * Refuses {@code text} when its fractional-second part is longer than {@code precision} allows.
     *
     * @param precision the facet, absent when the family leaves it unconstrained
     * @param text      the token as written, already matched against its family's shape
     * @param atom      the atom's name, for the diagnostic
     */
    static void check(Optional<BigInteger> precision, String text, String atom) {
        precision.ifPresent(allowed -> {
            int digits = fractionalDigits(text);
            if (BigInteger.valueOf(digits).compareTo(allowed) > 0) {
                throw new AtomValidationException("'" + text + "' states " + digits + " fractional-second "
                        + (digits == 1 ? "digit" : "digits") + " where this " + atom + " allows at most "
                        + allowed, "at most " + allowed + " fractional-second digits");
            }
        });
    }

    /** The digits between the fractional {@code .} and the offset, or zero where the token has no fraction. */
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
}
