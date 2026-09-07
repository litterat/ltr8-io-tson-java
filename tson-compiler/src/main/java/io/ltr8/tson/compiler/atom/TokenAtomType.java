package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;

/**
 * The two atoms whose reading depends on <b>how the author wrote the token</b>, not only on what it said.
 *
 * <p>Every other built-in family is a function of the text alone: {@code uuid} reads the same content
 * whether it arrived quoted or not, and [TSON-JSON] §5.1 makes that the whole of the JSON contract too --
 * a string's content is handed to the atom's own parser exactly as a TSON quoted token's text would be. So
 * {@link AtomType} takes a {@code String}, and one vocabulary serves both encodings.
 *
 * <p>These two cannot, and both are escape hatches rather than types:
 *
 * <ul>
 *   <li>{@link ValueParser} -- the kernel's {@code value}, decoded by [TSON-DATA] §4 base type resolution,
 *       whose §4.4 rule is <em>a quoted token is a string</em>. The form is the input.</li>
 *   <li>{@link RawTokenParser} -- {@code schema.meta.Token}, which <em>is</em> the token: text plus the form
 *       that produced it, because [TSON-SCHEMA] §8's resolved form records the spelling.</li>
 * </ul>
 *
 * <p>That they are exactly the positions where the two encodings legitimately differ is not a coincidence:
 * JSON has no token forms and reads a {@code value} position by [TSON-JSON] §5.7's own rule instead. So the
 * form-dependent half stays here with the text encoding, and the form-independent half is shared.
 */
public interface TokenAtomType<T> extends AtomType<T> {

    T read(TokenValue token) throws AtomParseException, AtomValidationException;

    Object read(TokenValue token, Class<?> target) throws AtomParseException, AtomValidationException;

    /**
     * Reading one of these from bare text means reading it as an <b>unquoted</b> token -- the only form a
     * caller holding a {@code String} and no form can be describing, since a quoted token's text has already
     * had its escapes processed and its form is exactly what was lost.
     */
    @Override
    default T read(String text) throws AtomParseException, AtomValidationException {
        return read(new TokenValue(text, io.ltr8.tson.compiler.ast.TokenForm.UNQUOTED));
    }

    @Override
    default Object read(String text, Class<?> target) throws AtomParseException, AtomValidationException {
        return read(new TokenValue(text, io.ltr8.tson.compiler.ast.TokenForm.UNQUOTED), target);
    }
}
