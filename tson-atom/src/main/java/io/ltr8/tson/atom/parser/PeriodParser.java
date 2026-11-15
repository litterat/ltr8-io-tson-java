package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.schema.temporal.PeriodType;
import java.math.BigInteger;
import java.time.Period;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates against meta's {@code period_type} constructor ([TSON-SCHEMA] §5.5's {@code period}
 * atom): a calendar span, as a signed integer number of months.
 *
 * <p>The grammar is RFC 3339 Appendix A's {@code duration} production restricted to an optional leading
 * {@code -}, then {@code P} followed by a Y component, an M component, or both in that order. No fraction,
 * no W or D component and no {@code T} part: {@code P1M15D}, {@code P1Y0D} and {@code P1YT1H} are all
 * errors. A span that is genuinely both calendar and clock is a record with a {@code period} field and a
 * {@code duration} field, which is the shape that keeps each half orderable.
 *
 * <p>{@code P1Y} and {@code P12M} are one value and {@code P0Y}, {@code P0M} and {@code -P0M} are one value,
 * so bounds compare the value and never the token. {@code multiple_of} is tested on the magnitude, so a
 * negative span is a multiple of a positive step.
 */
public record PeriodParser(PeriodType constraints) implements AtomTypeParser<Period> {

    /** §5.5's built-in annotation name -- {@code !period}. */
    public static final String TYPENAME = "period";

    /** {@code period => !temporal.period_type {}} -- the unconstrained period. */
    public static final PeriodParser UNCONSTRAINED = new PeriodParser(PeriodType.UNCONSTRAINED);

    public PeriodParser(Optional<Period> min, Optional<Period> max) {
        this(new PeriodType(min, max));
    }

    private static final Pattern PERIOD =
            Pattern.compile("(?<sign>-)?P(?:(?<years>\\d+)Y)?(?:(?<months>\\d+)M)?");

    @Override
    public Period read(String text) {
        Matcher m = PERIOD.matcher(text);
        if (!m.matches()) {
            throw new AtomParseException("'" + text + "' is not a valid period -- expected P with a Y "
                    + "component, an M component, or both in that order, and no D, W, T part or fraction "
                    + "(§5.5)", "a period");
        }
        if (m.group("years") == null && m.group("months") == null) {
            throw new AtomParseException("'" + text + "' is not a valid period -- at least one of the Y and M "
                    + "components is required (§5.5)", "a period");
        }

        BigInteger months = BigInteger.valueOf(12)
                .multiply(m.group("years") == null ? BigInteger.ZERO : new BigInteger(m.group("years")))
                .add(m.group("months") == null ? BigInteger.ZERO : new BigInteger(m.group("months")));
        if (m.group("sign") != null) {
            months = months.negate();
        }

        Period value = Period.ofMonths(months.intValueExact()).normalized();
        validate(value, text);
        return value;
    }

    private void validate(Period value, String text) {
        Bounds.check(value, text, MONTH_ORDER, Bounds.NOT_EXPECTED, constraints.min(),
                constraints.exclusiveMin(), constraints.max(), constraints.exclusiveMax());
        constraints.multipleOf().ifPresent(step -> {
            if (!PeriodType.isMultiple(value, step)) {
                throw new AtomValidationException("'" + text + "' is not a multiple of " + step,
                        "a multiple of " + step);
            }
        });
    }

    private static final Comparator<Period> MONTH_ORDER = Comparator.comparing(PeriodType::months);

    /** Written as years and months, years carrying every whole twelve -- the value's own normal form. */
    @Override
    public String write(Period value) {
        long months = value.toTotalMonths();
        if (months == 0) {
            return "P0M";
        }
        long magnitude = Math.abs(months);
        StringBuilder out = new StringBuilder(months < 0 ? "-P" : "P");
        if (magnitude / 12 != 0) {
            out.append(magnitude / 12).append('Y');
        }
        if (magnitude % 12 != 0) {
            out.append(magnitude % 12).append('M');
        }
        return out.toString();
    }

    /**
     * Its own value, or the period text -- this family's wire form is text, so a component keeping the
     * spelling it validated loses nothing. The value is read first either way: a text target chooses the
     * representation, never the rules.
     */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return AtomTypeParser.isTextTarget(target) ? asWrittenText() : natural(Period.class, target);
    }

}
