package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.Token;

/**
 * Reads a token <em>without</em> resolving it: the text plus the form that produced it, as {@link Token}.
 *
 * <p><b>The counterpart of {@link ValueParser}, for the one slot that parser cannot fill.</b> The kernel's
 * {@code value} primitive decodes ([TSON-DATA] §4) -- {@code 3} to an integer, {@code "3"} to a string --
 * which is what a document's data wants and what every other {@code value}-typed slot gets. A {@code
 * type_argument}'s value channel is bound to a {@link Token}, because §5.10 describes a type argument's
 * literal as a bare token rather than as the value it denotes, and a decoded host object cannot fill one.
 *
 * <p><b>Keeping the spelling is what §8.2 asks for, and it does not cost identity.</b> A value argument is
 * "recorded as written" so output round-trips it, while identity compares the value the token denotes under
 * §4 -- so {@code vector<float32, 255>} and {@code vector<float32, 0xFF>} stay one application. The
 * comparison is {@code NumericIdentity}'s, not this class's; this one only declines to decode.
 *
 * <p>Selected per slot rather than per type, by the bound component's own Java type -- see {@code
 * GroupUnionBindReader}. A {@code value}-typed field bound to anything else keeps {@link ValueParser}.
 */
public final class RawTokenParser implements AtomType<Token> {

    public static final RawTokenParser INSTANCE = new RawTokenParser();

    private RawTokenParser() {
    }

    @Override
    public Token read(TokenValue token) {
        return new Token(token.text(), switch (token.form()) {
            case UNQUOTED -> Token.Form.UNQUOTED;
            case SINGLE_LINE_QUOTED -> Token.Form.SINGLE_LINE_QUOTED;
            case MULTI_LINE_QUOTED -> Token.Form.MULTI_LINE_QUOTED;
        });
    }

    @Override
    public String write(Token value) {
        return value.text();
    }
}
