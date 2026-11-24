package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomValidationException;
import java.util.Comparator;
import java.util.Optional;

/**
 * An ordered family's bounds on read: two field groups, {@code ( min | exclusive_min )?} and {@code ( max |
 * exclusive_max )?}, and a value outside either is a validation error ([TSON-SCHEMA] §5.5, §5.7). The four
 * tests are written once, over the order the family supplies, so a family cannot carry an exclusive bound in its
 * body and leave it untested.
 */
final class Bounds {

    private Bounds() {
    }

    enum Bound {
        MIN(">= "), EXCLUSIVE_MIN("> "), MAX("<= "), EXCLUSIVE_MAX("< ");

        private final String relation;

        Bound(String relation) {
            this.relation = relation;
        }

        String expected(Object limit) {
            return relation + limit;
        }
    }

    /** The message after the quoted token; the diagnostic's {@code expected} is {@link Bound#expected} either way. */
    @FunctionalInterface
    interface Wording {
        String violation(Bound bound, Object limit);
    }

    static final Wording NOT_EXPECTED = (bound, limit) -> "is not " + bound.expected(limit);

    /** The exclusive ends are worded as every numeric family words them. */
    static final Wording CHRONOLOGICAL = (bound, limit) -> switch (bound) {
        case MIN -> "is before the minimum " + limit;
        case EXCLUSIVE_MIN -> "must be strictly greater than " + limit;
        case MAX -> "is after the maximum " + limit;
        case EXCLUSIVE_MAX -> "must be strictly less than " + limit;
    };

    static <T> void check(T value, String text, Comparator<? super T> order, Wording wording,
            Optional<T> min, Optional<T> exclusiveMin, Optional<T> max, Optional<T> exclusiveMax) {
        if (min.isPresent() && order.compare(value, min.get()) < 0) {
            throw violation(text, wording, Bound.MIN, min.get());
        }
        if (exclusiveMin.isPresent() && order.compare(value, exclusiveMin.get()) <= 0) {
            throw violation(text, wording, Bound.EXCLUSIVE_MIN, exclusiveMin.get());
        }
        if (max.isPresent() && order.compare(value, max.get()) > 0) {
            throw violation(text, wording, Bound.MAX, max.get());
        }
        if (exclusiveMax.isPresent() && order.compare(value, exclusiveMax.get()) >= 0) {
            throw violation(text, wording, Bound.EXCLUSIVE_MAX, exclusiveMax.get());
        }
    }

    private static AtomValidationException violation(String text, Wording wording, Bound bound, Object limit) {
        return new AtomValidationException("'" + text + "' " + wording.violation(bound, limit), bound.expected(limit));
    }
}
