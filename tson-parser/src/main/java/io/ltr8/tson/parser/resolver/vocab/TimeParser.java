package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.TimeType;

import java.time.OffsetTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses and validates against meta-kernel's {@code time_type} constructor (§5.4's {@code time}
 * atom, RFC 3339 {@code full-time}). Same shape-then-delegate pattern as {@link DateParser} --
 * {@link OffsetTime#parse} itself already gets RFC 3339's case-insensitive {@code T}/{@code Z}
 * allowance right natively (no extra work needed there, confirmed empirically) and correctly
 * requires the offset ({@code "10:15:30"} with no zone is rejected), but needs the same year-shape
 * guard {@link DateParser} does where a date is involved -- moot for a bare time, so the regex here
 * only needs to anchor the overall shape, not work around a JDK leniency the way {@code
 * DateParser}'s does. Holds a {@link TimeType} -- the pure constraint values, unchanged by this
 * split -- rather than declaring those fields itself.
 *
 * <p>One real, unavoidable gap: RFC 3339's grammar permits {@code time-second} up to {@code 60}
 * (leap-second accommodation), but {@code java.time} has no leap-second concept at all --
 * {@code OffsetTime.parse("23:59:60Z")} throws regardless of what this class does before calling it.
 * A spec-legal leap-second token is therefore rejected here as a parse error; there's no reasonable
 * fix short of a from-scratch time representation just for this one case, so it's left as a
 * documented limitation rather than solved.
 *
 * <p>{@code precision}/{@code require_timezone} (meta.tn1) are not modeled -- no built-in instance
 * sets either, {@code precision}'s own required semantics (exact vs. maximum fractional-digit count)
 * aren't clear from the field name alone, and {@code require_timezone: false} would need an entirely
 * different parse path (a bare offset-less time) this class doesn't have.
 */
public record TimeParser(TimeType constraints) implements AtomType<OffsetTime> {

    /** §5.4's built-in annotation name -- {@code !time}. */
    public static final String TYPENAME = "time";

    /** {@code time => !time_type {}} -- the unconstrained time, §5.4's {@code !time}. */
    public static final TimeParser UNCONSTRAINED = new TimeParser(TimeType.UNCONSTRAINED);

    public TimeParser(Optional<OffsetTime> min, Optional<OffsetTime> max) {
        this(new TimeType(min, max));
    }

    private static final Pattern FULL_TIME = Pattern.compile("\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})");

    @Override
    public OffsetTime read(TokenValue token) {
        String text = token.text();
        if (!FULL_TIME.matcher(text).matches()) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid time -- expected RFC 3339 full-time, HH:MM:SS[.fraction](Z|+HH:MM) (§5.4)");
        }
        OffsetTime value;
        try {
            value = OffsetTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new AtomParseException("'" + text + "' is not a valid time (§5.4): " + e.getMessage());
        }
        validate(value, text);
        return value;
    }

    /** {@link OffsetTime#toString()} already gives RFC 3339's exact {@code full-time} form. */
    @Override
    public String write(OffsetTime value) {
        return value.toString();
    }

    private void validate(OffsetTime value, String text) {
        constraints.min().ifPresent(m -> {
            if (value.isBefore(m)) {
                throw new AtomValidationException("'" + text + "' is before the minimum " + m);
            }
        });
        constraints.max().ifPresent(m -> {
            if (value.isAfter(m)) {
                throw new AtomValidationException("'" + text + "' is after the maximum " + m);
            }
        });
    }
}
