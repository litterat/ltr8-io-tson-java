package io.ltr8.tson.schema.temporal;

import io.ltr8.tson.schema.atom.AtomCoherence;

import java.util.List;
import java.util.Optional;

/**
 * {@code fraction_digits}' coherence on {@link TimeType}, {@link DateTimeType} and {@link DurationType}
 * ([TSON-SCHEMA] §5.5, §7.4): their value spaces carry nine fractional-second digits, so a grid finer than that
 * names no digit for a schema to ask about.
 */
final class FractionDigits {

    private static final int MAX = 9;

    private FractionDigits() {
    }

    static void checkCoherent(List<String> out, Optional<Long> fractionDigits, String family) {
        AtomCoherence.checkNonNegative(out, "fraction_digits", fractionDigits);
        fractionDigits.filter(digits -> digits > MAX).ifPresent(digits -> out.add("fraction_digits " + digits
                + " is finer than the " + MAX + " fractional-second digits a " + family + " carries"));
    }
}
