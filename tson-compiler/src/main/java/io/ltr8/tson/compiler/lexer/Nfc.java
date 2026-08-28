package io.ltr8.tson.compiler.lexer;

import java.text.Normalizer;

/**
 * NFC, applied where the series makes it identity rather than content.
 *
 * <p>[TSON-DATA] §7.2.1 normalises <em>unquoted</em> tokens and leaves quoted ones exact, because a quoted
 * token is usually a string value and a value's content is its own. But §2.5 and §2.6 define the identity of
 * a <b>name</b> and of a <b>scalar map key</b> by their NFC-normalised text, whichever form spelled them:
 * "two field names are identical if they produce the same NFC-normalized string after escape processing --
 * {@code name} and {@code "name"} are the same field name". So a quoted field name is a name, not a value,
 * and comparing it raw makes {@code café} in NFC and NFD two fields where the section makes them one.
 *
 * <p>Which is why this is applied at the point a token becomes a <em>name</em> rather than at lex time: a
 * quoted string value must still keep its exact content, and only the name and key positions ask for
 * identity. See {@code TsonDataStream}'s {@code FieldName} emission and the map readers' key comparison.
 *
 * <p>The common case allocates nothing: almost every name is already normalised -- an unquoted one always
 * is, the lexer having rejected it otherwise -- so the check short-circuits before {@link
 * Normalizer#normalize} would build a second string.
 */
public final class Nfc {

    private Nfc() {
    }

    /** {@code text} in NFC, returning it unchanged when it already is. */
    public static String of(String text) {
        return Normalizer.isNormalized(text, Normalizer.Form.NFC)
                ? text
                : Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    /**
     * The identity of a decoded scalar key: NFC for a {@code String}, the value itself otherwise. §2.6 makes
     * NFC-normalised text the *minimum* a processor must relate ("textual identity is the parser's
     * minimum"), and a decoding processor "compares decoded values", which must therefore detect at least
     * what the textual rule does -- so a decoded string key is compared normalised even though it is stored
     * as written.
     */
    public static Object keyOf(Object decoded) {
        return decoded instanceof String text ? of(text) : decoded;
    }
}
