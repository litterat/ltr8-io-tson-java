package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.DateTimeType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses and validates against meta-kernel's {@code datetime_type} constructor (§5.4's {@code
 * datetime} atom, RFC 3339 {@code date-time}). Same shape-then-delegate pattern as {@link
 * DateParser}/{@link TimeParser} -- the shape regex exists specifically to reject {@link
 * OffsetDateTime#parse}'s own leniency on the year (ISO 8601's "extended year" form, confirmed
 * empirically: {@code OffsetDateTime.parse("+12025-03-13T10:00:00Z")} succeeds, which RFC 3339's
 * {@code full-date} grammar -- exactly 4 digits, no sign -- doesn't permit). The case-insensitive
 * {@code T}/{@code Z} and required-offset behavior are both already correct natively, same as
 * {@link TimeParser}, and the same leap-second gap {@link TimeParser} documents applies here too
 * (inherited from the same {@code full-time} production). Holds a {@link DateTimeType} -- the pure
 * constraint values, unchanged by this split -- rather than declaring those fields itself.
 *
 * <p>{@code precision}/{@code require_timezone} are modelled on the body and refused here -- see the
 * constructor. Carrying them is what keeps the body faithful to the constructor's resolved shape; refusing
 * them is what keeps a schema from stating a constraint this parser would ignore.
 */
public record DateTimeParser(DateTimeType constraints) implements AtomType<OffsetDateTime> {

    public DateTimeParser {
        // Carried on the body, unenforced here -- so a schema that sets one is refused rather than accepted
        // and quietly ignored. An UnsupportedOperationException is the gap classification, and since a gap
        // now travels as a Diagnostic, the author is told which declaration and why while the rest of their
        // schema still gets its verdict.
        if (constraints.precision().isPresent()) {
            throw new UnsupportedOperationException("'datetime' does not enforce 'precision' yet, so a schema "
                    + "setting it would be accepted without the constraint being applied -- the spec does not "
                    + "say whether it bounds the fractional-second digits exactly or at most, and this "
                    + "implementation will not guess. Drop it, or constrain the value another way");
        }
        if (constraints.requireTimezone().isPresent()) {
            throw new UnsupportedOperationException("'datetime' does not enforce 'require_timezone' yet, so a "
                    + "schema setting it would be accepted without the constraint being applied. RFC 3339 "
                    + "requires an offset on every value this atom accepts, so 'true' is already the "
                    + "behaviour; 'false' needs an offset-less parse this atom does not have");
        }
    }

    /** §5.4's built-in annotation name -- {@code !datetime}. */
    public static final String TYPENAME = "datetime";

    /** {@code datetime => !datetime_type {}} -- the unconstrained datetime, §5.4's {@code !datetime}. */
    public static final DateTimeParser UNCONSTRAINED = new DateTimeParser(DateTimeType.UNCONSTRAINED);

    public DateTimeParser(Optional<OffsetDateTime> min, Optional<OffsetDateTime> max) {
        this(new DateTimeType(min, max, Optional.empty(), Optional.empty()));
    }

    private static final Pattern DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})");

    @Override
    public OffsetDateTime read(TokenValue token) {
        String text = token.text();
        if (!DATE_TIME.matcher(text).matches()) {
            throw new AtomParseException("'" + text + "' is not a valid datetime -- expected RFC 3339 "
                    + "date-time, YYYY-MM-DDTHH:MM:SS[.fraction](Z|+HH:MM) (§5.4)", "an RFC 3339 date-time");
        }
        OffsetDateTime value;
        try {
            value = OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new AtomParseException("'" + text + "' is not a valid datetime (§5.4): " + e.getMessage(),
                    "an RFC 3339 date-time");
        }
        validate(value, text);
        return value;
    }

    /** {@link OffsetDateTime#toString()} already gives RFC 3339's exact {@code date-time} form. */
    @Override
    public String write(OffsetDateTime value) {
        return value.toString();
    }

    private void validate(OffsetDateTime value, String text) {
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
