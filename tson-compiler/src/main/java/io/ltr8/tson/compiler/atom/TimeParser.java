package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
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
 * <p>{@code precision}/{@code require_timezone} are modelled on {@link TimeType} and refused here (see the
 * constructor). They are carried because a body must mirror its constructor's resolved shape -- a field with
 * no component is one this model would silently lose -- and refused because neither is enforced:
 * {@code precision}'s required semantics (exact vs. maximum fractional-digit count) are not settled by the
 * spec, and {@code require_timezone: false} needs an offset-less parse path this class does not have.
 */
public record TimeParser(TimeType constraints) implements AtomType<OffsetTime> {

    public TimeParser {
        // Carried on the body, unenforced here -- so a schema that sets one is refused rather than accepted
        // and quietly ignored. An UnsupportedOperationException is the gap classification, and since a gap
        // now travels as a Diagnostic, the author is told which declaration and why while the rest of their
        // schema still gets its verdict.
        if (constraints.precision().isPresent()) {
            throw new UnsupportedOperationException("'time' does not enforce 'precision' yet, so a schema "
                    + "setting it would be accepted without the constraint being applied -- the spec does not "
                    + "say whether it bounds the fractional-second digits exactly or at most, and this "
                    + "implementation will not guess. Drop it, or constrain the value another way");
        }
        if (constraints.requireTimezone().isPresent()) {
            throw new UnsupportedOperationException("'time' does not enforce 'require_timezone' yet, so a "
                    + "schema setting it would be accepted without the constraint being applied. RFC 3339 "
                    + "requires an offset on every value this atom accepts, so 'true' is already the "
                    + "behaviour; 'false' needs an offset-less parse this atom does not have");
        }
    }

    /** §5.4's built-in annotation name -- {@code !time}. */
    public static final String TYPENAME = "time";

    /** {@code time => !time_type {}} -- the unconstrained time, §5.4's {@code !time}. */
    public static final TimeParser UNCONSTRAINED = new TimeParser(TimeType.UNCONSTRAINED);

    public TimeParser(Optional<OffsetTime> min, Optional<OffsetTime> max) {
        this(new TimeType(min, max, Optional.empty(), Optional.empty()));
    }

    private static final Pattern FULL_TIME = Pattern.compile("\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})");

    @Override
    public OffsetTime read(TokenValue token) {
        String text = token.text();
        if (!FULL_TIME.matcher(text).matches()) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid time -- expected RFC 3339 full-time, "
                            + "HH:MM:SS[.fraction](Z|+HH:MM) (§5.4)", "an RFC 3339 full-time");
        }
        OffsetTime value;
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
}
