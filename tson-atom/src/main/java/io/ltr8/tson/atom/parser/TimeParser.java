package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.schema.meta.TimeType;
import java.time.OffsetTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses and validates against meta's {@code time_type} constructor (§5.4's {@code time}
 * atom, RFC 3339 {@code full-time}). Same shape-then-delegate pattern as {@link DateParser} --
 * {@link OffsetTime#parse} itself already gets RFC 3339's case-insensitive {@code T}/{@code Z}
 * allowance right natively (no extra work needed there, confirmed empirically) and correctly
 * requires the offset ({@code "10:15:30"} with no zone is rejected), but needs the same year-shape
 * guard {@link DateParser} does where a date is involved -- moot for a bare time, so the regex here
 * only needs to anchor the overall shape, not work around a JDK leniency the way {@code
 * DateParser}'s does. Holds a {@link TimeType} -- the pure constraint values, unchanged by this
 * split -- rather than declaring those fields itself.
 *
 * <p><b>A leap second is refused.</b> RFC 3339's grammar admits {@code time-second} {@code 60}, but core's
 * {@code time} is the time of day on {@code [00:00:00, 24:00:00)}, and {@code 23:59:60} lies outside it
 * (SPEC-FEEDBACK.md #20). {@link OffsetTime#parse} refuses it as well, so the refusal is a parse error.
 *
 * <p>{@code precision} constrains the value, not the spelling (§5.5), through {@link FractionalSeconds}:
 * {@code 12:00:00.500} is admitted under {@code precision: 1}, being the half-second.
 */
public record TimeParser(TimeType constraints) implements AtomTypeParser<OffsetTime> {

    /** §5.4's built-in annotation name -- {@code !time}. */
    public static final String TYPENAME = "time";

    /** {@code time => !time_type {}} -- the unconstrained time, §5.4's {@code !time}. */
    public static final TimeParser UNCONSTRAINED = new TimeParser(TimeType.UNCONSTRAINED);

    public TimeParser(Optional<OffsetTime> min, Optional<OffsetTime> max) {
        this(new TimeType(min, max));
    }

    private static final Pattern FULL_TIME = Pattern.compile("\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})");

    @Override
    public OffsetTime read(String text) {
        if (!FULL_TIME.matcher(text).matches()) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid time -- expected RFC 3339 full-time, "
                            + "HH:MM:SS[.fraction](Z|+HH:MM) (§5.4)", "an RFC 3339 full-time");
        }
        OffsetTime value;
        FractionalSeconds.checkRepresentable(text, "time");
        try {
            value = OffsetTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new AtomParseException("'" + text + "' is not a valid time (§5.4): " + e.getMessage(),
                    "an RFC 3339 full-time");
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
        FractionalSeconds.check(constraints.precision(), value.getNano(), text, "time");
        constraints.min().ifPresent(m -> {
            if (value.isBefore(m)) {
                throw new AtomValidationException("'" + text + "' is before the minimum " + m, ">= " + m);
            }
        });
        constraints.max().ifPresent(m -> {
            if (value.isAfter(m)) {
                throw new AtomValidationException("'" + text + "' is after the maximum " + m, "<= " + m);
            }
        });
    }

    /**
     * Its own value, or the RFC 3339 text -- this family's wire form is text, so a component keeping the
     * spelling it validated loses nothing. The value is read first either way: a text target chooses the
     * representation, never the rules.
     */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return AtomTypeParser.isTextTarget(target) ? asWrittenText() : natural(OffsetTime.class, target);
    }

}
