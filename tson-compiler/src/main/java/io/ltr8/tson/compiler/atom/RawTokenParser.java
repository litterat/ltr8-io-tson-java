package io.ltr8.tson.compiler.atom;

import java.util.Optional;

import io.ltr8.tson.atom.AtomType;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.Token;

/**
 * Reads a token <em>without</em> resolving it: the text plus the form that produced it, as {@link Token}.
 *
 * <p><b>The counterpart of {@link ValueParser}, for the slot that parser cannot fill.</b> The kernel's
 * {@code value} primitive decodes ([TSON-DATA] §4) -- {@code 3} to an integer, {@code "3"} to a string --
 * which is what a document's data wants and what a {@code value}-typed slot bound to a decoded host gets.
 * {@code record_field.value}, a field's fixed or default value, is bound to a {@link Token}: it is read later
 * by the field's declared type, and resolved output records it as written, so a decoded host object cannot
 * fill it.
 *
 * <p><b>Keeping the spelling is what §8.2 asks for, and it does not cost identity.</b> Two spellings of one
 * number are compared as the value the token denotes under §4, by {@code NumericIdentity}, not this class;
 * this one only declines to decode.
 *
 * <p>Selected per slot rather than per type, by the bound component's own Java type -- see {@code
 * GroupUnionBindReader}. A {@code value}-typed field bound to anything else keeps {@link ValueParser}.
 */
public final class RawTokenParser implements TokenAtomType<Token> {

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
    public Object read(TokenValue token, Class<?> target) {
        return read(token);
    }

    @Override
    public String write(Token value) {
        return value.text();
    }

    /**
     * The token itself and nothing else: this atom exists to keep the spelling §8's resolved form records,
     * so a target that is not a {@link Token} would be asking for the very thing it preserves.
     *
     * <p>Written out rather than reached through {@code AtomTypeParser}'s helpers, which are the built-in
     * vocabulary's own. A form-sensitive atom is not one of that vocabulary -- it reads a {@code TokenValue}
     * where every family reads text -- and three lines here cost less than a shared supertype spanning both.
     */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return target.isAssignableFrom(Token.class) ? Optional.of(this) : Optional.empty();
    }
}
