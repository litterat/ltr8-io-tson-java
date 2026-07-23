package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.NumberForms;
import io.ltr8.tson.parser.resolver.NumberGrammar;
import io.ltr8.tson.parser.resolver.NumberNarrowing;
import io.ltr8.tson.schema.meta.DecimalType;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Parses and validates against meta-kernel's {@code decimal_type} constructor (§5.6's {@code
 * number} atom -- SQL's exact tier, ISO/IEC 11404 {@code scaled}). Accepts only {@code
 * integer}/{@code float} forms (§7.6) -- unlike {@link IntegerParser}, no {@code based-integer}
 * either, and unlike {@link FloatParser}, no {@code hex-float} or {@code special-value}: "{@code
 * !number}, being exact, does not accept the special values" (§5.6). The value is preserved exactly
 * as written, never rounded -- this is the atom {@code AtomBinder}'s existing untyped-float path
 * already implements the exactness for (a bare decimal token already binds to {@code BigDecimal}
 * without loss); this type exists to apply the same exact-preservation contract when the token
 * additionally carries an explicit {@code !number} annotation, plus {@code decimal_type}'s
 * constraint vocabulary (bounds, {@code multiple_of}, digit-count limits) on top. Holds a {@link
 * DecimalType} -- the pure constraint values, unchanged by this split -- rather than declaring
 * those fields itself.
 */
public record DecimalParser(DecimalType constraints) implements AtomType<BigDecimal> {

    /** §5.6's built-in annotation name -- {@code !number}. */
    public static final String TYPENAME = "number";

    /** {@code number => !decimal_type {}} -- the unconstrained exact number, §5.6's {@code !number}. */
    public static final DecimalParser UNCONSTRAINED = new DecimalParser(DecimalType.UNCONSTRAINED);

    public DecimalParser(Optional<BigDecimal> min, Optional<BigDecimal> exclusiveMin, Optional<BigDecimal> max,
                          Optional<BigDecimal> exclusiveMax, Optional<BigDecimal> multipleOf,
                          Optional<Integer> totalDigits, Optional<Integer> fractionDigits) {
        this(new DecimalType(min, exclusiveMin, max, exclusiveMax, multipleOf, totalDigits, fractionDigits));
    }

    @Override
    public BigDecimal read(TokenValue token) {
        return (BigDecimal) read(token, BigDecimal.class);
    }

    @Override
    public Object read(TokenValue token, Class<?> target) {
        return NumberNarrowing.narrowDecimal(readExact(token), target);
    }

    @Override
    public String write(BigDecimal value) {
        return value.toString();
    }

    private BigDecimal readExact(TokenValue token) {
        String text = token.text();
        NumberForm form = NumberGrammar.tryParse(text)
                .filter(f -> f instanceof NumberForm.IntegerForm || f instanceof NumberForm.FloatForm)
                .orElseThrow(() -> new AtomParseException("'" + text + "' is not a valid exact number -- "
                        + "only integer and float forms are accepted (§5.6); !number does not accept "
                        + "based-integer or the special values"));

        BigDecimal exact = (form instanceof NumberForm.IntegerForm intForm)
                ? new BigDecimal(NumberForms.toBigInteger(intForm))
                : NumberForms.toBigDecimal((NumberForm.FloatForm) form);
        validate(exact, text);
        return exact;
    }

    private void validate(BigDecimal value, String text) {
        constraints.min().ifPresent(m -> {
            if (value.compareTo(m) < 0) {
                throw new AtomValidationException("'" + text + "' is less than the minimum " + m);
            }
        });
        constraints.exclusiveMin().ifPresent(m -> {
            if (value.compareTo(m) <= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly greater than " + m);
            }
        });
        constraints.max().ifPresent(m -> {
            if (value.compareTo(m) > 0) {
                throw new AtomValidationException("'" + text + "' is greater than the maximum " + m);
            }
        });
        constraints.exclusiveMax().ifPresent(m -> {
            if (value.compareTo(m) >= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly less than " + m);
            }
        });
        constraints.multipleOf().ifPresent(m -> {
            if (value.remainder(m).compareTo(BigDecimal.ZERO) != 0) {
                throw new AtomValidationException("'" + text + "' is not a multiple of " + m);
            }
        });
        constraints.totalDigits().ifPresent(td -> {
            if (value.precision() > td) {
                throw new AtomValidationException(
                        "'" + text + "' has more than the maximum " + td + " total significant digits");
            }
        });
        constraints.fractionDigits().ifPresent(fd -> {
            // scale() can be negative (e.g. 1E+2 has scale -2, a whole number with no fraction
            // digits at all, not -2 of them) -- clamp at 0 before comparing.
            if (Math.max(value.scale(), 0) > fd) {
                throw new AtomValidationException(
                        "'" + text + "' has more than the maximum " + fd + " digits after the decimal point");
            }
        });
    }
}
