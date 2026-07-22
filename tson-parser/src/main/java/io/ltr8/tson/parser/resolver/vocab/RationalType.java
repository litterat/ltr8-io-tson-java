package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.NumberGrammar;
import io.ltr8.tson.parser.resolver.RationalForm;

import java.math.BigInteger;
import java.util.Optional;

/**
 * meta-kernel's {@code rational_type} constructor (§5.6's {@code rational} atom). Accepts only the
 * {@code rational} grammar form (§7.6) -- {@code "2/3"}, always quoted in practice since {@code /}
 * is outside the unquoted token profile (§5.6), though {@link #read} doesn't check {@link
 * TokenValue#form()} itself, matching every other atom here (§5.2: "whether quoting is required is
 * a lexical property of the content, not of the atom").
 *
 * <p>Has exactly one legitimate host representation ({@link Rational} itself), so unlike {@link
 * IntegerType}/{@link DecimalType}/{@link FloatType} this doesn't override {@link #read(TokenValue,
 * Class)} -- {@link AtomType}'s default (read the natural value, require the target to accept it)
 * already gives the right behavior, including the {@code target == Rational.class} case a {@code
 * tson-mapper} bridge registration relies on (see {@link Rational}'s Javadoc for the recommended way
 * to bind {@code !rational} to a richer third-party type instead of this minimal one).
 */
public record RationalType(
        Optional<Rational> min,
        Optional<Rational> exclusiveMin,
        Optional<Rational> max,
        Optional<Rational> exclusiveMax,
        Optional<Rational> multipleOf) implements AtomType<Rational> {

    /** {@code rational => !rational_type {}} -- the unconstrained rational, §5.6's {@code !rational}. */
    public static final RationalType UNCONSTRAINED = new RationalType(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public RationalType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    @Override
    public Rational read(TokenValue token) {
        String text = token.text();
        RationalForm form = NumberGrammar.tryRational(text).orElseThrow(() -> new AtomParseException(
                "'" + text + "' is not a valid rational -- expected numerator/denominator, e.g. \"2/3\" (§7.6)"));

        BigInteger numerator = new BigInteger(form.numerator().replace("_", ""));
        if (form.sign().filter(s -> s == NumberForm.Sign.MINUS).isPresent()) {
            numerator = numerator.negate();
        }
        BigInteger denominator = new BigInteger(form.denominator().replace("_", ""));
        Rational value = new Rational(numerator, denominator);
        validate(value, text);
        return value;
    }

    private void validate(Rational value, String text) {
        min.ifPresent(m -> {
            if (value.compareTo(m) < 0) {
                throw new AtomValidationException("'" + text + "' is less than the minimum " + m);
            }
        });
        exclusiveMin.ifPresent(m -> {
            if (value.compareTo(m) <= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly greater than " + m);
            }
        });
        max.ifPresent(m -> {
            if (value.compareTo(m) > 0) {
                throw new AtomValidationException("'" + text + "' is greater than the maximum " + m);
            }
        });
        exclusiveMax.ifPresent(m -> {
            if (value.compareTo(m) >= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly less than " + m);
            }
        });
        multipleOf.ifPresent(m -> {
            // value / m = (a/b) / (c/d) = (a*d) / (b*c) -- an integer iff (a*d) mod (b*c) == 0
            // (b*c is always positive, both denominators being positive by construction).
            BigInteger lhs = value.numerator().multiply(m.denominator());
            BigInteger rhs = value.denominator().multiply(m.numerator());
            if (rhs.signum() == 0 || lhs.remainder(rhs).signum() != 0) {
                throw new AtomValidationException("'" + text + "' is not a multiple of " + m);
            }
        });
    }
}
