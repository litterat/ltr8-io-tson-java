package io.ltr8.tson.compiler.atom;

import java.util.Optional;

import io.ltr8.tson.atom.AtomType;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.Token;

/**
 * Reads a token <em>without</em> resolving it: the text plus the form that produced it, as {@link Token}.
 *
 * <p><b>Why it reads a token, when every family of {@link AtomType} reads text.</b> {@code meta.record_field.value}, a
 * field's fixed or default value, is bound to a {@link Token}: it is read later by the field's declared type, and
 * resolved output records it as written, so a decoded host object cannot fill it. The form is the point, and so this
 * is the text encoding's and no atom of the shared vocabulary: [TSON-JSON] has no token forms.
 *
 * <p><b>Keeping the spelling is what §8.2 asks for, and it does not cost identity.</b> Two spellings of one number are
 * compared as the value the token denotes under §4, by the reader of the type they are read against, not this class;
 * this one only declines to decode.
 *
 * <p>Selected per slot rather than per type, by the bound component's own Java type -- see {@code
 * GroupUnionBindReader} and {@code RecordBindReader.tokenAware}.
 */
public final class RawTokenParser implements AtomType<Token> {

    public static final RawTokenParser INSTANCE = new RawTokenParser();

    private RawTokenParser() {
    }

    public Token read(TokenValue token) {
        return new Token(token.text(), switch (token.form()) {
            case UNQUOTED -> Token.Form.UNQUOTED;
            case SINGLE_LINE_QUOTED -> Token.Form.SINGLE_LINE_QUOTED;
            case MULTI_LINE_QUOTED -> Token.Form.MULTI_LINE_QUOTED;
        });
    }

    /**
     * Reading a token from bare text means reading it as an <b>unquoted</b> one -- the only form a caller holding a
     * {@code String} and no form can be describing, since a quoted token's text has had its escapes processed and its
     * form is exactly what was lost.
     */
    @Override
    public Token read(String text) {
        return read(new TokenValue(text, TokenForm.UNQUOTED));
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
