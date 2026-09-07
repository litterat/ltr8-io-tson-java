package io.ltr8.tson.json.lexer;

/**
 * The complete token vocabulary of RFC 8259 -- six structural characters, three literal names, and
 * the two value tokens. Nothing is reserved for later: [TSON-JSON] §3.1 adds no syntax to JSON and
 * takes none away, so this enum is closed by the RFC rather than by this implementation.
 *
 * <p>The extensions a lenient JSON parser admits are deliberately absent and are not tokens here: no
 * comment, no trailing comma, no unquoted member name, no {@code NaN}/{@code Infinity}. §3.1's
 * profile is RFC 8259 exactly, tightened, and JEP 540 refuses the same set for the same reason.
 */
public enum JsonTokenType {

    /** <code>{</code> -- RFC 8259 §2 {@code begin-object}. */
    BEGIN_OBJECT,

    /** <code>}</code> -- {@code end-object}. */
    END_OBJECT,

    /** {@code [} -- {@code begin-array}. */
    BEGIN_ARRAY,

    /** {@code ]} -- {@code end-array}. */
    END_ARRAY,

    /** {@code :} -- {@code name-separator}. */
    NAME_SEPARATOR,

    /** {@code ,} -- {@code value-separator}. */
    VALUE_SEPARATOR,

    /** A quoted string (RFC 8259 §7), escape-decoded -- see {@code JsonLexer.text()}. */
    STRING,

    /** A number (RFC 8259 §6), kept as the exact source lexeme -- see {@code JsonLexer.text()}. */
    NUMBER,

    /** The literal name {@code true}. */
    TRUE,

    /** The literal name {@code false}. */
    FALSE,

    /** The literal name {@code null}. */
    NULL,

    /** End of input. */
    EOF
}
