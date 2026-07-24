package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.NumberGrammar;
import io.ltr8.tson.parser.resolver.RationalForm;
import io.ltr8.tson.schema.meta.Rational;
import io.ltr8.tson.schema.meta.RationalType;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Parses and validates against meta-kernel's {@code rational_type} constructor (§5.6's {@code
 * rational} atom). Accepts only the {@code rational} grammar form (§7.6) -- {@code "2/3"}, always
 * quoted in practice since {@code /} is outside the unquoted token profile (§5.6), though {@link
 * #read} doesn't check {@link TokenValue#form()} itself, matching every other atom here (§5.2:
 * "whether quoting is required is a lexical property of the content, not of the atom"). Holds a
 * {@link RationalType} -- the pure constraint values, unchanged by this split -- rather than
 * declaring those fields itself.
 *
 * <p>Has exactly one legitimate host representation ({@link Rational} itself), so unlike {@link
 * IntegerParser}/{@link DecimalParser}/{@link FloatParser} this doesn't override {@link
 * #read(TokenValue, Class)} -- {@link AtomType}'s default (read the natural value, require the
 * target to accept it) already gives the right behavior, including the {@code target ==
 * Rational.class} case a {@code io.ltr8.tson.parser.mapper} bridge registration relies on (see {@link Rational}'s
 * Javadoc for the recommended way to bind {@code !rational} to a richer third-party type instead
 * of this minimal one).
 */
public record RationalParser(RationalType constraints) implements AtomType<Rational> {

    /** §5.6's built-in annotation name -- {@code !rational}. */
    public static final String TYPENAME = "rational";

    /** {@code rational => !rational_type {}} -- the unconstrained rational, §5.6's {@code !rational}. */
    public static final RationalParser UNCONSTRAINED = new RationalParser(RationalType.UNCONSTRAINED);

    public RationalParser(Optional<Rational> min, Optional<Rational> exclusiveMin, Optional<Rational> max,
                           Optional<Rational> exclusiveMax, Optional<Rational> multipleOf) {
        this(new RationalType(min, exclusiveMin, max, exclusiveMax, multipleOf));
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

    /** {@link Rational#toString()} already gives exactly {@code numerator/denominator}. */
    @Override
    public String write(Rational value) {
        return value.toString();
    }

    private void validate(Rational value, String text) {
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
