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
 * duration} atom): elapsed time, as a signed <b>exact decimal</b> number of seconds. The lexical form permits
 * a decimal fraction on the seconds component and nowhere else, so no non-terminating fraction is writable --
 * {@code PT1/3S} is not a token -- and every duration is therefore a terminating decimal count of seconds.
 * That is {@code number}'s value space measured in seconds, which is what makes {@code precision} exactly
 * {@code fraction_digits} on that count and {@code multiple_of} exactly {@code number}'s own: the facets are
 * definitions rather than rules of their own.
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
 * <p><b>Both ends of the value space are fixed at a signed 64-bit count of nanoseconds</b> -- see {@link
 * #MAX_FRACTION_DIGITS}. Within that, {@code precision} constrains the <em>value</em> and never the spelling,
 * as {@code time_type}'s own {@code @doc} states for the same facet: {@code PT0.50S} is a whole number of
 * tenths and {@code precision: 1} admits it. Nothing is truncated; a value genuinely off the grid is
 * rejected. {@code multiple_of} is tested on the magnitude, so {@code -PT30M} is a multiple of {@code PT15M}.
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

    /**
     * The finest resolution the value space carries, and the widest magnitude: a signed 64-bit count of
     * nanoseconds. RFC 3339's {@code time-secfrac} is {@code "." 1*DIGIT} and its seconds component {@code
     * 1*DIGIT}, so the grammar alone admits values no host runtime has a type for -- a nanosecond is the
     * finest resolution any of them offers and several are coarser, and Go's {@code time.Duration} is a
     * signed 64-bit nanosecond count exactly. A range that depends on which implementation read the document
     * is not a range, so both ends are fixed here rather than left to what {@code java.time.Duration}
     * happens to reach (±292 <em>billion</em> years, three orders past the tightest host).
     *
     * <p>Stated as a magnitude rather than the asymmetric {@code int64} range, so negating an admitted
     * duration always yields an admitted one.
     *
     * <p>Longer spans are not lost, they are spelled better: a span of centuries is a calendar span and is a
     * {@code period}, and a count of SI seconds beyond that is a physical quantity, where {@code number} in
     * the unit the schema names says what a duration cannot.
     *
     * <p>The ceiling also makes {@link Duration#toNanos} total for every admitted value, which is what {@link
     * DurationType#isMultiple} tests on: the range is the range {@code toNanos} has, so the overflow it used
     * to throw past ±292 years is now unreachable rather than merely unlikely.
     */
    private static final int MAX_FRACTION_DIGITS = 9;

    /** The ceiling as a count of seconds -- {@link #MAX_FRACTION_DIGITS} says why this number and not another. */
    private static final BigDecimal MAX_SECONDS =
            new BigDecimal(BigInteger.valueOf(Long.MAX_VALUE)).movePointLeft(MAX_FRACTION_DIGITS);

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

        String fraction = m.group("fraction");
        if (fraction != null && fraction.length() > MAX_FRACTION_DIGITS) {
            throw new AtomParseException("'" + text + "' states " + fraction.length() + " fractional-second "
                    + "digits, where a duration carries at most " + MAX_FRACTION_DIGITS + " -- one nanosecond is "
                    + "the finest resolution the value space admits (§5.5)",
                    "at most " + MAX_FRACTION_DIGITS + " fractional-second digits");
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
        validate(value, seconds, text);
        return value;
    }

    private static BigDecimal scaled(String digits, BigDecimal secondsPerUnit) {
        return digits == null ? BigDecimal.ZERO : new BigDecimal(digits).multiply(secondsPerUnit);
    }

    /**
     * The value as a {@link Duration}, once the magnitude is known to fit one. The fraction is already
     * capped at nine digits by the grammar, so the only thing left to refuse here is a span past the
     * ceiling -- which {@link Duration} itself would take (its {@code long} seconds reach ±292 billion
     * years) and which no implementation with a nanosecond-counting duration type could.
     */
    private static Duration toDuration(BigDecimal seconds, String text) {
        if (seconds.abs().compareTo(MAX_SECONDS) > 0) {
            throw new AtomValidationException("'" + text + "' is longer than " + MAX_SECONDS + " seconds, the "
                    + "widest magnitude a duration carries -- a span this long is a calendar span (a period) "
                    + "or a quantity in a unit a schema names (§5.5)",
                    "a magnitude of at most " + MAX_SECONDS + " seconds");
        }
        BigInteger nanos = seconds.movePointRight(MAX_FRACTION_DIGITS).toBigIntegerExact();
        return Duration.ofSeconds(nanos.divide(NANOS_PER_SECOND).longValueExact(),
                nanos.remainder(NANOS_PER_SECOND).longValueExact());
    }

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    private void validate(Duration value, BigDecimal seconds, String text) {
        // `precision: N` is `fraction_digits: N` on the seconds count, which is a constraint on the value and
        // not on the spelling -- so PT0.50S is a whole number of tenths and `precision: 1` admits it, the
        // same rule and the same wording time_type's own @doc already carries.
        constraints.precision().ifPresent(precision -> {
            if (BigInteger.valueOf(Math.max(seconds.stripTrailingZeros().scale(), 0)).compareTo(precision) > 0) {
                throw new AtomValidationException("'" + text + "' is not a whole number of 10^-" + precision
                        + " seconds, which is the resolution this duration admits",
                        "a whole number of 10^-" + precision + " seconds");
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
