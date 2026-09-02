package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.base.BaseTypeResolver;
import io.ltr8.tson.compiler.base.BaseValue;
import io.ltr8.tson.compiler.base.NumberForm;
import io.ltr8.tson.compiler.base.NumberForms;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Parses meta-kernel's {@code value} instance of the {@code unit} atom constructor (§4.2, §8.1) --
 * the "escape hatch primitive": per its own kernel doc, "the result of base type resolution
 * ([TSON-DATA] §4) applied to a source token, with no further interpretation... the host runtime is
 * responsible for type-checking values at use site." Unlike {@link IdentifierParser} (raw lexical text,
 * unconstrained) this actually runs {@link BaseTypeResolver} -- boolean/number/string, §4.5's
 * fixed order -- and narrows the result to the natural Java host type each {@link BaseValue}
 * variant implies: {@link Boolean}, {@link BigInteger}/{@link BigDecimal} (or {@link Double} for the
 * two special numeric forms, {@code .nan}/{@code .inf}, which have no exact intermediate), or
 * {@link String}. <b>None of them is {@code null}</b>: §4 resolves three classes and absence is not
 * one of them, so a {@code value}-typed position holding {@code _} is an absent position rather than a
 * value this parser ever reads or writes. No caller-specified target -- {@code value} is declared to have
 * no constraint vocabulary and is explicitly "not narrowable" (its own kernel doc), so there is only
 * ever the one, natural representation.
 *
 * <p>See {@link IdentifierParser}'s own Javadoc for why this class -- along with {@code void}'s own
 * {@code io.ltr8.tson.compiler.reader.VoidReader} -- exists as a separate,
 * name-keyed specialization rather than one shared {@code unit} compiler: the kernel's own text says
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
            // Unreachable: BaseTypeResolver resolves a token, and no token is the absent sentinel.
            case BaseValue.AbsentValue ignored -> throw new IllegalStateException("base resolution produced absence");
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
            // `value` has no null inhabitant to write: absence is `_`, and an emitter writes it as absence.
            case null -> throw new IllegalArgumentException("the absent sentinel is not a 'value'; emit '_' instead");
            case Boolean b -> b.toString();
            case Double d when d.isNaN() -> ".nan";
            case Double d when d == Double.POSITIVE_INFINITY -> ".inf";
            case Double d when d == Double.NEGATIVE_INFINITY -> "-.inf";
            case BigInteger i -> i.toString();
            case BigDecimal d -> d.toString();
            case String s -> s;
            default -> throw new IllegalArgumentException("not a value this compiler ever produced: " + value);
        };
    }
}
