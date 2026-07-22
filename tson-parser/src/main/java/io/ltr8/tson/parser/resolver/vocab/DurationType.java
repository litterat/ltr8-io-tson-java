package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Period;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * meta-kernel's {@code duration_type} constructor (§5.4's {@code duration} atom, ISO 8601's {@code
 * PnYnMnDTnHnMnS}). A from-scratch parser -- confirmed empirically that neither {@link Period#parse}
 * nor {@link Duration#parse} covers the combined form (see {@link IsoDuration}'s Javadoc) -- built as
 * a single anchored regex with each designator's digits as its own named group, rather than
 * delegating shape recognition to either JDK parser and inheriting whichever ISO 8601 extensions it
 * happens to accept beyond the strict grammar (both {@code Period.parse}/{@code Duration.parse}
 * accept a leading {@code -} sign and lowercase {@code p}/{@code t}, neither of which TSON's own
 * {@code PnYnMnDTnHnMnS} notation shows).
 *
 * <p>{@code P}/{@code PT} alone (every designator absent) is rejected -- checked after the regex
 * match succeeds, since the regex's own optionality would otherwise accept either as a technically
 * complete but empty match.
 *
 * <p>Not implemented: ISO 8601's alternative week form ({@code PnW}, e.g. {@code P3W}) -- TSON's own
 * table gives the accepted format as literally {@code PnYnMnDTnHnMnS}, with no {@code W}, so this
 * reads that as exhaustive rather than illustrative; see {@code SPEC-FEEDBACK.md} #12 for why that's
 * a real ambiguity, not a confident call.
 */
public record DurationType(Optional<IsoDuration> min, Optional<IsoDuration> max) implements AtomType<IsoDuration> {

    /** {@code duration => !duration_type {}} -- the unconstrained duration, §5.4's {@code !duration}. */
    public static final DurationType UNCONSTRAINED = new DurationType(Optional.empty(), Optional.empty());

    private static final Pattern DURATION = Pattern.compile(
            "P(?:(?<years>\\d+)Y)?(?:(?<months>\\d+)M)?(?:(?<days>\\d+)D)?"
            + "(?:T(?:(?<hours>\\d+)H)?(?:(?<minutes>\\d+)M)?(?:(?<seconds>\\d+(?:\\.\\d+)?)S)?)?");

    @Override
    public IsoDuration read(TokenValue token) {
        String text = token.text();
        Matcher m = DURATION.matcher(text);
        if (!m.matches()) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid ISO 8601 duration -- expected PnYnMnDTnHnMnS (§5.4)");
        }

        String years = m.group("years");
        String months = m.group("months");
        String days = m.group("days");
        String hours = m.group("hours");
        String minutes = m.group("minutes");
        String seconds = m.group("seconds");

        if (years == null && months == null && days == null && hours == null && minutes == null && seconds == null) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid ISO 8601 duration -- at least one designator is required (§5.4)");
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

    private static Duration secondsToDuration(String secondsText) {
        BigDecimal seconds = new BigDecimal(secondsText);
        long whole = seconds.longValue();
        BigDecimal fraction = seconds.subtract(BigDecimal.valueOf(whole));
        // Nanosecond is Duration's own finest grain -- round rather than reject anything more
        // precise than that, the same rounding-to-the-representable-grid reasoning FloatType uses.
        long nanos = fraction.movePointRight(9).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return Duration.ofSeconds(whole, nanos);
    }
}
