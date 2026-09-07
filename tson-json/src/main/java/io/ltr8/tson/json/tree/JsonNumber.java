package io.ltr8.tson.json.tree;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A JSON number, held as the <b>exact source lexeme</b>.
 *
 * <p>[TSON-JSON] §3.1 imposes no precision limit and requires that a decoder "MUST preserve a number's
 * digits into the host value the position's atom contract defines; an implementation that cannot
 * represent the digits MUST error, never round silently". §5.3 adds that an exact number's digits and
 * scale survive: {@code 199.90} encodes as {@code 199.90}, not {@code 199.9}. Both are promises about
 * digits, so digits are what this holds — a {@code double} field would have broken them before any
 * rule could keep them, and a {@link BigDecimal} field would have lost {@code 1e2} against {@code 100}.
 * JEP 540 reaches the same place by the same route: its own advice for arbitrary precision is
 * {@code new BigDecimal(number.toString())}.
 *
 * <p><b>Equality is over the lexeme, not the numeric value.</b> {@code 1} and {@code 1.0} and
 * {@code 1e0} are three distinct {@code JsonNumber}s. That looks wrong and is the honest layering:
 * [TSON-SCHEMA] §5.5 makes equality a property of a <em>value space</em>, and a value space comes from
 * a type, which this layer has none of. Under a schema the same three are one {@code number}, and the
 * schema-directed decode is where that is decided. A caller who wants numeric comparison here has
 * {@link #toBigDecimal()} in one call, and {@code compareTo} on the result is the comparison they mean.
 */
public record JsonNumber(String literal) implements JsonValue {

    public JsonNumber {
        if (literal == null || literal.isEmpty()) {
            throw new IllegalArgumentException("a JSON number has digits");
        }
    }

    /** From a lexeme, which MUST be an RFC 8259 number — unchecked here, since {@code JsonLexer} is what checks it. */
    public static JsonNumber of(String literal) {
        return new JsonNumber(literal);
    }

    public static JsonNumber of(long value) {
        return new JsonNumber(Long.toString(value));
    }

    /** From a {@link BigDecimal}, keeping its scale — {@code 199.90} stays two places, per §5.3. */
    public static JsonNumber of(BigDecimal value) {
        return new JsonNumber(value.toPlainString());
    }

    /**
     * The value exactly, at full precision — the one call JEP 540 tells a caller to spell out.
     *
     * <p>Never lossy: the lexeme is RFC 8259, which {@link BigDecimal} parses in full, exponent and all.
     */
    public BigDecimal toBigDecimal() {
        return new BigDecimal(literal);
    }

    /** @throws JsonValueException if this number has a fractional part */
    public BigInteger toBigInteger() {
        try {
            return toBigDecimal().toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw new JsonValueException("%s is not an integer".formatted(literal));
        }
    }

    /**
     * @throws JsonValueException unless the value is exactly representable as an {@code int}
     *
     * <p>Exactly, not nearly: §3.1's "error, never round silently" applies to every narrowing this
     * class offers, and the two that can be exact are checked rather than truncated.
     */
    @Override
    public int asInt() {
        try {
            return toBigDecimal().intValueExact();
        } catch (ArithmeticException e) {
            throw new JsonValueException("%s is not exactly representable as an int".formatted(literal));
        }
    }

    /** @throws JsonValueException unless the value is exactly representable as a {@code long} */
    @Override
    public long asLong() {
        try {
            return toBigDecimal().longValueExact();
        } catch (ArithmeticException e) {
            throw new JsonValueException("%s is not exactly representable as a long".formatted(literal));
        }
    }

    /**
     * The nearest {@code double}, rounding where the digits do not fit.
     *
     * <p>The one conversion here that is allowed to lose, because rounding onto the binary grid is what
     * the approximate families' own contract does (§5.4, roundTiesToEven). A caller reaching for this
     * has asked for a {@code double}; a caller who must not lose has {@link #toBigDecimal()}.
     */
    @Override
    public double asDouble() {
        return Double.parseDouble(literal);
    }

    /** The lexeme, unchanged — so a parsed number re-emits the digits it arrived with (§5.3). */
    @Override
    public String toString() {
        return literal;
    }
}
