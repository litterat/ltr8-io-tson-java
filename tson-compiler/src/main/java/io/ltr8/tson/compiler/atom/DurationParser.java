package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.DurationType;
import io.ltr8.tson.schema.atom.IsoDuration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Period;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates against meta-kernel's {@code duration_type} constructor (§5.4's {@code
 * duration} atom, ISO 8601's {@code PnYnMnDTnHnMnS}). A from-scratch compiler -- confirmed
 * empirically that neither {@link Period#parse} nor {@link Duration#parse} covers the combined
 * form (see {@link IsoDuration}'s Javadoc) -- built as a single anchored regex with each
 * designator's digits as its own named group, rather than delegating shape recognition to either
 * JDK compiler and inheriting whichever ISO 8601 extensions it happens to accept beyond the strict
 * grammar (both {@code Period.parse}/{@code Duration.parse} accept a leading {@code -} sign and
 * lowercase {@code p}/{@code t}, neither of which TSON's own {@code PnYnMnDTnHnMnS} notation
 * shows). Holds a {@link DurationType} -- the pure constraint values, unchanged by this split --
 * rather than declaring those fields itself.
 *
 * <p>{@code P}/{@code PT} alone (every designator absent) is rejected -- checked after the regex
 * match succeeds, since the regex's own optionality would otherwise accept either as a technically
 * complete but empty match.
 *
 * <p>Not implemented: ISO 8601's alternative week form ({@code PnW}, e.g. {@code P3W}) -- TSON's own
 * table gives the accepted format as literally {@code PnYnMnDTnHnMnS}, with no {@code W}, so this
 * reads that as exhaustive rather than illustrative; see {@code SPEC-FEEDBACK.md} #1, still open, for why
 * that's a real ambiguity rather than a confident call.
 *
 * <p>{@link DurationType#min}/{@link DurationType#max} are raw {@code String} text, not {@link
 * IsoDuration} (matching the {@code TextType.pattern}/{@code UriType.pattern} precedent, so the
 * field binds generically with no {@code DataBridge}) -- {@link #parseDuration} is the one place the
 * {@code PnYnMnDTnHnMnS} grammar is recognized, reused by both {@link #read} (the instance value)
 * and, on demand, by anything needing the parsed form of a constraint string. No min/max bound
 * validation is performed yet -- {@link IsoDuration}'s own Javadoc explains why (not {@code
 * Comparable}: a calendar-based duration has no fixed length to compare against a clock-based one).
 */
public record DurationParser(DurationType constraints) implements AtomType<IsoDuration> {

    /** §5.4's built-in annotation name -- {@code !duration}. */
    public static final String TYPENAME = "duration";

    /** {@code duration => !duration_type {}} -- the unconstrained duration, §5.4's {@code !duration}. */
    public static final DurationParser UNCONSTRAINED = new DurationParser(DurationType.UNCONSTRAINED);

    public DurationParser(Optional<String> min, Optional<String> max) {
        this(new DurationType(min, max));
    }

    private static final Pattern DURATION = Pattern.compile(
            "P(?:(?<years>\\d+)Y)?(?:(?<months>\\d+)M)?(?:(?<days>\\d+)D)?"
            + "(?:T(?:(?<hours>\\d+)H)?(?:(?<minutes>\\d+)M)?(?:(?<seconds>\\d+(?:\\.\\d+)?)S)?)?");

    @Override
    public IsoDuration read(TokenValue token) {
        return parseDuration(token.text());
    }

    /** The {@code PnYnMnDTnHnMnS} grammar itself, shared by {@link #read} and constraint parsing. */
    private static IsoDuration parseDuration(String text) {
        Matcher m = DURATION.matcher(text);
        if (!m.matches()) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid ISO 8601 duration -- expected PnYnMnDTnHnMnS (§5.4)",
                    "an ISO 8601 duration");
        }

        String years = m.group("years");
        String months = m.group("months");
        String days = m.group("days");
        String hours = m.group("hours");
        String minutes = m.group("minutes");
        String seconds = m.group("seconds");

        if (years == null && months == null && days == null && hours == null && minutes == null && seconds == null) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid ISO 8601 duration -- at least one designator is required (§5.4)",
                    "an ISO 8601 duration");
        }

        Period calendarPart = Period.of(
                years == null ? 0 : Integer.parseInt(years),
                months == null ? 0 : Integer.parseInt(months),
                days == null ? 0 : Integer.parseInt(days));

        Duration clockPart = Duration.ZERO;
        if (hours != null) {
            clockPart = clockPart.plusHours(Long.parseLong(hours));
        }
        if (minutes != null) {
            clockPart = clockPart.plusMinutes(Long.parseLong(minutes));
        }
        if (seconds != null) {
            clockPart = clockPart.plus(secondsToDuration(seconds));
        }

        return new IsoDuration(calendarPart, clockPart);
    }

    /**
     * §5.4's {@code PnYnMnDTnHnMnS}, unsigned only -- matching {@link #read}'s own grammar exactly,
     * including its fractional-seconds handling (nanoseconds only ever enter {@code clockPart}
     * through the seconds designator via {@link #secondsToDuration}, so they're read back out
     * through it too). {@code clockPart} is never day-normalized ({@link #read} accumulates it via
     * {@code plusHours}/{@code plusMinutes}/{@code plus} with no rollover), so this uses {@link
     * Duration#getSeconds()} (the running total) with its own hour/minute/second decomposition, not
     * {@code toHoursPart()}/{@code toMinutesPart()} (which assume a day-normalized value and would
     * silently wrap a duration of, say, 30 hours down to 6 -- confirmed empirically, not assumed).
     */
    @Override
    public String write(IsoDuration value) {
        Period period = value.calendarPart();
        Duration clock = value.clockPart();

        StringBuilder sb = new StringBuilder("P");
        if (period.getYears() != 0) {
            sb.append(period.getYears()).append('Y');
        }
        if (period.getMonths() != 0) {
            sb.append(period.getMonths()).append('M');
        }
        if (period.getDays() != 0) {
            sb.append(period.getDays()).append('D');
        }

        long totalSeconds = clock.getSeconds();
        int nanos = clock.getNano();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours != 0 || minutes != 0 || seconds != 0 || nanos != 0) {
            sb.append('T');
            if (hours != 0) {
                sb.append(hours).append('H');
            }
            if (minutes != 0) {
                sb.append(minutes).append('M');
            }
            if (seconds != 0 || nanos != 0) {
                sb.append(seconds);
                if (nanos != 0) {
                    sb.append('.').append(fractionDigits(nanos));
                }
                sb.append('S');
            }
        }

        if (sb.length() == 1) {
            // At least one designator is required (read() rejects a bare "P"/"PT").
            sb.append("T0S");
        }
        return sb.toString();
    }

    private static String fractionDigits(int nanos) {
        String nineDigits = String.format("%09d", nanos);
        int end = nineDigits.length();
        while (end > 1 && nineDigits.charAt(end - 1) == '0') {
            end--;
        }
        return nineDigits.substring(0, end);
    }

    private static Duration secondsToDuration(String secondsText) {
        BigDecimal seconds = new BigDecimal(secondsText);
        long whole = seconds.longValue();
        BigDecimal fraction = seconds.subtract(BigDecimal.valueOf(whole));
        // Nanosecond is Duration's own finest grain -- round rather than reject anything more
        // precise than that, the same rounding-to-the-representable-grid reasoning FloatParser uses.
        long nanos = fraction.movePointRight(9).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return Duration.ofSeconds(whole, nanos);
    }
}
