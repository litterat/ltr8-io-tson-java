package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.base.BaseTypeResolver;
import io.ltr8.tson.atom.number.BaseValue;
import io.ltr8.tson.atom.number.NumberForm;
import io.ltr8.tson.atom.number.NumberForms;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * [TSON-DATA] §4 base type resolution of one token, narrowed to a host value: what a position nothing types holds. It
 * runs {@link BaseTypeResolver} -- boolean/number/string, §4.5's fixed order -- and narrows the result to the natural
 * Java host type each {@link BaseValue} variant implies: {@link Boolean}, {@link BigInteger}/{@link BigDecimal} (or
 * {@link Double} for the two special numeric forms, {@code .nan}/{@code .inf}, which have no exact intermediate), or
 * {@link String}. <b>None of them is {@code null}</b>: §4 resolves three classes and absence is not one of them.
 *
 * <p>It reads a token rather than text because base type resolution depends on how the token was written, §4.4's rule
 * being that a quoted token is a string, and so it is the text encoding's and no atom of the shared vocabulary.
 */
public final class ValueParser {

    public static final ValueParser INSTANCE = new ValueParser();

    private ValueParser() {
    }

    public Object read(TokenValue token) {
        return narrow(BaseTypeResolver.resolve(token));
    }

    private static Object narrow(BaseValue value) {
        return switch (value) {
            // Unreachable: BaseTypeResolver resolves a token, and no token is the void sentinel.
            case BaseValue.VoidValue ignored -> throw new IllegalStateException("base resolution produced absence");
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
}
