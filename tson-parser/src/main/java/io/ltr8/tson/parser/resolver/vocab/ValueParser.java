package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.BaseTypeResolver;
import io.ltr8.tson.parser.resolver.BaseValue;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.NumberForms;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Parses meta-kernel's {@code value} instance of the {@code unit} atom constructor (§4.2, §8.1) --
 * the "escape hatch primitive": per its own kernel doc, "the result of base type resolution
 * ([TSON-DATA] §4) applied to a source token, with no further interpretation... the host runtime is
 * responsible for type-checking values at use site." Unlike {@link TokenParser} (raw lexical text,
 * unconstrained) this actually runs {@link BaseTypeResolver} -- null/boolean/number/string, §4.5's
 * fixed order -- and narrows the result to the natural Java host type each {@link BaseValue}
 * variant implies: {@code null}, {@link Boolean}, {@link BigInteger}/{@link BigDecimal} (or {@link
 * Double} for the two special numeric forms, {@code .nan}/{@code .inf}, which have no exact
 * intermediate), or {@link String}. No caller-specified target -- {@code value} is declared to have
 * no constraint vocabulary and is explicitly "not narrowable" (its own kernel doc), so there is only
 * ever the one, natural representation.
 *
 * <p>See {@link TokenParser}'s own Javadoc for why this class -- along with {@code void}'s own
 * {@code io.ltr8.tson.parser.resolver.schema.compiled.VoidParser} -- exists as a separate,
 * name-keyed specialization rather than one shared {@code unit} parser: the kernel's own text says
 * {@code value}/{@code token}/{@code void} are "distinguished by name and prose-level parsing
 * contract, not by schema shape."
 */
public final class ValueParser implements AtomType<Object> {

    public static final ValueParser INSTANCE = new ValueParser();

    private ValueParser() {
    }

    @Override
    public Object read(TokenValue token) {
        return narrow(BaseTypeResolver.resolve(token));
    }

    private static Object narrow(BaseValue value) {
        return switch (value) {
            case BaseValue.NullValue ignored -> null;
            case BaseValue.BooleanValue b -> b.value();
            case BaseValue.StringValue s -> s.text();
            case BaseValue.NumberValue n -> narrowNumber(n.form());
        };
    }

    private static Object narrowNumber(NumberForm form) {
        if (form instanceof NumberForm.SpecialValueForm special) {
            return switch (special.kind()) {
                case NAN -> Double.NaN;
                case INFINITY -> special.sign().filter(s -> s == NumberForm.Sign.MINUS).isPresent()
                        ? Double.NEGATIVE_INFINITY
                        : Double.POSITIVE_INFINITY;
            };
        }
        if (form instanceof NumberForm.IntegerForm || form instanceof NumberForm.BasedIntegerForm) {
            return NumberForms.toBigInteger(form);
        }
        if (form instanceof NumberForm.FloatForm floatForm) {
            return NumberForms.toBigDecimal(floatForm);
        }
        throw new IllegalArgumentException("unrecognised number form: " + form);
    }

    @Override
    public String write(Object value) {
        return switch (value) {
            case null -> "null";
            case Boolean b -> b.toString();
            case Double d when d.isNaN() -> ".nan";
            case Double d when d == Double.POSITIVE_INFINITY -> ".inf";
            case Double d when d == Double.NEGATIVE_INFINITY -> "-.inf";
            case BigInteger i -> i.toString();
            case BigDecimal d -> d.toString();
            case String s -> s;
            default -> throw new IllegalArgumentException("not a value this parser ever produced: " + value);
        };
    }
}
