package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.DateType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses and validates against meta-kernel's {@code date_type} constructor (§5.4's {@code date}
 * atom, RFC 3339 {@code full-date}). Validates the token's shape itself before calling {@link
 * LocalDate#parse} -- confirmed empirically that {@code LocalDate.parse} alone is more lenient than
 * RFC 3339's {@code full-date = date-fullyear "-" date-month "-" date-mday} (exactly 4-digit year,
 * no sign): {@code LocalDate.parse("+12025-03-13")} succeeds, using ISO 8601's "extended year"
 * format, which RFC 3339's grammar doesn't have. The same "validate shape ourselves, then delegate"
 * pattern as hex-float/UUID/base64. Calendar validity itself (leap years, day-of-month ranges) is
 * left to {@code LocalDate.parse}, which already gets it right. Holds a {@link DateType} -- the
 * pure constraint values, unchanged by this split -- rather than declaring those fields itself.
 */
public record DateParser(DateType constraints) implements AtomType<LocalDate> {

    /** §5.4's built-in annotation name -- {@code !date}. */
    public static final String TYPENAME = "date";

    /** {@code date => !date_type {}} -- the unconstrained date, §5.4's {@code !date}. */
    public static final DateParser UNCONSTRAINED = new DateParser(DateType.UNCONSTRAINED);

    public DateParser(Optional<LocalDate> min, Optional<LocalDate> max) {
        this(new DateType(min, max));
    }

    private static final Pattern FULL_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    @Override
    public LocalDate read(TokenValue token) {
        String text = token.text();
        if (!FULL_DATE.matcher(text).matches()) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid date -- expected RFC 3339 full-date, YYYY-MM-DD (§5.4)");
        }
        LocalDate value;
        try {
            value = LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new AtomParseException("'" + text + "' is not a valid date (§5.4): " + e.getMessage());
        }
        validate(value, text);
        return value;
    }

    /** {@link LocalDate#toString()} already gives RFC 3339's exact {@code full-date} form. */
    @Override
    public String write(LocalDate value) {
        return value.toString();
    }

    private void validate(LocalDate value, String text) {
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
