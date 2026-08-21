package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.Token;

/**
 * Reads a token <em>without</em> resolving it: the text plus the form that produced it, as {@link Token}.
 *
 * <p><b>The counterpart of {@link ValueParser}, for the one thing that parser is wrong for.</b> The kernel's
 * {@code value} primitive normally decodes ([TSON-DATA] §4) -- {@code 3} to an integer, {@code "3"} to a
 * string -- which is what a document's data wants. A {@code type_argument}'s value channel wants the opposite:
 * {@code box<3>} and {@code box<"3">} apply different arguments and must stay apart, and the form is the only
 * thing that separates them once the text is equal. Decoding first and rebuilding a token afterwards cannot
 * recover it.
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
