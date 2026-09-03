package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.DurationType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates against meta's {@code duration_type} constructor ([TSON-SCHEMA] §5.5's {@code
 * duration} atom): elapsed time, as a signed rational number of seconds.
 *
 * <p><b>The grammar is RFC 3339 Appendix A's {@code duration} production with three extensions and one
 * restriction</b>, and the restriction is what makes the family orderable. Extensions: an optional leading
 * {@code -}; a fraction on the seconds component only, written with {@code .} ({@code PT0.5S}, never {@code
 * PT1.5H} or {@code PT0,5S}); and any subset of D, H, M, S may be omitted provided at least one component is
 * present and order is kept, so {@code PT1H30S} is admitted as {@link Duration} itself writes it.
 * Restriction: the Y and month-M components are <b>not</b> admitted -- {@code P1M} is one month and belongs
 * to {@code period}, a minute being {@code PT1M}. {@code PnW} stands alone as in the ABNF, so {@code P1W2D}
 * is an error.
 *
 * <p>A day is exactly 86400 s and a week 7 days, so {@code PT90M}, {@code PT1H30M} and {@code P0DT5400S} are
 * one value, and {@code PT0S}, {@code P0D} and {@code -PT0S} are one value. That is why bounds compare the
 * value and never the token, and why this family can enforce them at all: while one type carried both
 * calendar and clock components the order was partial and the bounds it declared went unchecked.
 *
 * <p>{@code precision} bounds fractional-second digits on the written token, as {@code time_type}'s does --
 * a validation constraint, nothing is truncated. {@code multiple_of} is tested on the magnitude, so {@code
 * -PT30M} is a multiple of {@code PT15M}.
 */
public record DurationParser(DurationType constraints) implements AtomType<Duration> {

    /** §5.5's built-in annotation name -- {@code !duration}. */
    public static final String TYPENAME = "duration";

    /** {@code duration => !duration_type {}} -- the unconstrained duration. */
    public static final DurationParser UNCONSTRAINED = new DurationParser(DurationType.UNCONSTRAINED);

    public DurationParser(Optional<Duration> min, Optional<Duration> max) {
        this(new DurationType(min, max));
    }

    /**
     * The week form and the day/time form are alternatives, never mixed: {@code PnW} stands alone in the
     * ABNF. Everything else is a subset in order, with the fraction confined to the seconds component.
     */
    private static final Pattern DURATION = Pattern.compile(
            "(?<sign>-)?P(?:(?<weeks>\\d+)W"
            + "|(?:(?<days>\\d+)D)?(?:T(?:(?<hours>\\d+)H)?(?:(?<minutes>\\d+)M)?"
            + "(?:(?<seconds>\\d+(?:\\.(?<fraction>\\d+))?)S)?)?)");

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);
    private static final BigDecimal SECONDS_PER_WEEK = BigDecimal.valueOf(604_800);
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600);
    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);

    @Override
    public Duration read(TokenValue token) {
        String text = token.text();
        Matcher m = DURATION.matcher(text);
        if (!m.matches()) {
            throw new AtomParseException("'" + text + "' is not a valid duration -- expected the RFC 3339 "
                    + "Appendix A form without Y or month-M components (a month is a period, a minute is "
                    + "PT1M), the week form standing alone (§5.5)", "a duration");
        }
        if (m.group("weeks") == null && m.group("days") == null && m.group("hours") == null
                && m.group("minutes") == null && m.group("seconds") == null) {
            throw new AtomParseException("'" + text + "' is not a valid duration -- at least one component is "
                    + "required (§5.5)", "a duration");
        }

        BigDecimal seconds = BigDecimal.ZERO
                .add(scaled(m.group("weeks"), SECONDS_PER_WEEK))
                .add(scaled(m.group("days"), SECONDS_PER_DAY))
                .add(scaled(m.group("hours"), SECONDS_PER_HOUR))
                .add(scaled(m.group("minutes"), SECONDS_PER_MINUTE))
                .add(m.group("seconds") == null ? BigDecimal.ZERO : new BigDecimal(m.group("seconds")));
        if (m.group("sign") != null) {
            seconds = seconds.negate();
        }

        Duration value = toDuration(seconds, text);
        validate(value, m.group("fraction"), text);
        return value;
    }

    private static BigDecimal scaled(String digits, BigDecimal secondsPerUnit) {
        return digits == null ? BigDecimal.ZERO : new BigDecimal(digits).multiply(secondsPerUnit);
    }

    /**
     * The value as a {@link Duration}. Nanosecond resolution is the series' own for fractional seconds --
     * {@code time_type} and {@code datetime_type} work at it for the same component -- so a fraction finer
     * than a nanosecond is refused rather than silently rounded, which would make a bound comparison lie.
     */
    private static Duration toDuration(BigDecimal seconds, String text) {
        BigDecimal nanos = seconds.movePointRight(9);
        if (nanos.stripTrailingZeros().scale() > 0) {
            throw new AtomParseException("'" + text + "' is finer than nanosecond resolution, which is what a "
                    + "duration value carries (§5.5)", "a duration of at most nanosecond resolution");
        }
        BigInteger total = nanos.toBigIntegerExact();
        return Duration.ofSeconds(total.divide(BigInteger.valueOf(1_000_000_000L)).longValueExact(),
                total.remainder(BigInteger.valueOf(1_000_000_000L)).longValueExact());
    }

    private void validate(Duration value, String fraction, String text) {
        constraints.precision().ifPresent(precision -> {
            int digits = fraction == null ? 0 : fraction.length();
            if (BigInteger.valueOf(digits).compareTo(precision) > 0) {
                throw new AtomValidationException("'" + text + "' has " + digits
                        + " fractional-second digits, more than the maximum " + precision,
                        "at most " + precision + " fractional-second digits");
            }
        });
        constraints.min().ifPresent(min -> require(value.compareTo(min) >= 0, text, ">= " + min));
        constraints.exclusiveMin().ifPresent(min -> require(value.compareTo(min) > 0, text, "> " + min));
        constraints.max().ifPresent(max -> require(value.compareTo(max) <= 0, text, "<= " + max));
        constraints.exclusiveMax().ifPresent(max -> require(value.compareTo(max) < 0, text, "< " + max));
        constraints.multipleOf().ifPresent(step -> require(DurationType.isMultiple(value, step), text,
                "a multiple of " + step));
    }

    private static void require(boolean holds, String text, String expected) {
        if (!holds) {
            throw new AtomValidationException("'" + text + "' is not " + expected, expected);
        }
    }

    /**
     * Written in the form {@link Duration} itself uses -- a day is not a distinct unit of the value, so
     * hours carry it. The seconds component takes the fraction, and only it can.
     */
    @Override
    public String write(Duration value) {
        if (value.isZero()) {
            return "PT0S";
        }
        Duration magnitude = value.abs();
        StringBuilder out = new StringBuilder(value.isNegative() ? "-PT" : "PT");
        long hours = magnitude.toHours();
        int minutes = magnitude.toMinutesPart();
        int seconds = magnitude.toSecondsPart();
        int nanos = magnitude.toNanosPart();
        if (hours != 0) {
            out.append(hours).append('H');
        }
        if (minutes != 0) {
            out.append(minutes).append('M');
        }
        if (seconds != 0 || nanos != 0) {
            out.append(seconds);
            if (nanos != 0) {
                out.append('.').append(String.format("%09d", nanos).replaceFirst("0+$", ""));
            }
            out.append('S');
        }
        return out.toString();
    }
}
