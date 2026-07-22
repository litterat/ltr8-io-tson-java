package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * meta-kernel's {@code datetime_type} constructor (§5.4's {@code datetime} atom, RFC 3339 {@code
 * date-time}). Same shape-then-delegate pattern as {@link DateType}/{@link TimeType} -- the shape
 * regex exists specifically to reject {@link OffsetDateTime#parse}'s own leniency on the year
 * (ISO 8601's "extended year" form, confirmed empirically: {@code
 * OffsetDateTime.parse("+12025-03-13T10:00:00Z")} succeeds, which RFC 3339's {@code full-date}
 * grammar -- exactly 4 digits, no sign -- doesn't permit). The case-insensitive {@code T}/{@code Z}
 * and required-offset behavior are both already correct natively, same as {@link TimeType}, and the
 * same leap-second gap {@link TimeType} documents applies here too (inherited from the same {@code
 * full-time} production).
 *
 * <p>{@code precision}/{@code require_timezone} not modeled, same reasoning as {@link TimeType}.
 */
public record DateTimeType(Optional<OffsetDateTime> min, Optional<OffsetDateTime> max) implements AtomType<OffsetDateTime> {

    /** {@code datetime => !datetime_type {}} -- the unconstrained datetime, §5.4's {@code !datetime}. */
    public static final DateTimeType UNCONSTRAINED = new DateTimeType(Optional.empty(), Optional.empty());

    private static final Pattern DATE_TIME = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([Zz]|[+-]\\d{2}:\\d{2})");

    @Override
    public OffsetDateTime read(TokenValue token) {
        String text = token.text();
        if (!DATE_TIME.matcher(text).matches()) {
            throw new AtomParseException("'" + text + "' is not a valid datetime -- expected RFC 3339 "
                    + "date-time, YYYY-MM-DDTHH:MM:SS[.fraction](Z|+HH:MM) (§5.4)");
        }
        OffsetDateTime value;
        try {
            value = OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new AtomParseException("'" + text + "' is not a valid datetime (§5.4): " + e.getMessage());
        }
        validate(value, text);
        return value;
    }

    private void validate(OffsetDateTime value, String text) {
        min.ifPresent(m -> {
            if (value.isBefore(m)) {
                throw new AtomValidationException("'" + text + "' is before the minimum " + m);
            }
        });
        max.ifPresent(m -> {
            if (value.isAfter(m)) {
                throw new AtomValidationException("'" + text + "' is after the maximum " + m);
            }
        });
    }
}
