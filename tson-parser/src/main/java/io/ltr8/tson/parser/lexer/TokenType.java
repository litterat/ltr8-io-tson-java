package io.ltr8.tson.parser.lexer;

/**
 * The token kinds produced by the TSON lexer (spec §7.2, §7.3). This is the
 * complete token vocabulary for the whole TSON series — higher parts (the
 * schema grammar) introduce no new tokens or lexer modes (§1.3).
 */
public enum TokenType {

    /** {@code "..."} — a single-line quoted token (§7.2.2). */
    SINGLE_LINE_STRING,

    /** {@code """ ... """} — a multi-line quoted token (§7.2.3). */
    MULTI_LINE_STRING,

    /** An unquoted token: identifiers, numbers, dates, etc. (§7.1, §7.3). */
    UNQUOTED,

    /** {@code _} — the absent sentinel (§2.9). */
    ABSENT,

    // Structural delimiters (§7.2 rule 4)
    LBRACE, RBRACE, LBRACKET, RBRACKET, COLON, COMMA,

    /** {@code =>} — the map entry separator (§7.2.4). */
    MAP_ARROW,

    /** {@code !!} — begins a configuration directive (§3.3, §7.2.4). */
    DIRECTIVE,

    /** {@code ..} — the range token (§7.2.4). Reserved; no role in data values. */
    RANGE,

    // Special tokens (§7.2.5). Fourteen characters, all Pattern_Syntax.
    // Only BANG (type prefix) and AT (annotation prefix) have a role in
    // data values; the rest are reserved by the schema grammar and are
    // parse errors wherever a data value is expected.

    /** {@code !} — type annotation prefix (§3.2), or first char of {@code !!}. */
    BANG,

    /** {@code @} — annotation prefix (§3.1). */
    AT,

    AMPERSAND,
    LESS_THAN,
    GREATER_THAN,
    QUESTION,
    TILDE,

    /** {@code =} not followed by {@code >}. Reserved; not a map arrow. */
    EQUAL,

    PIPE,
    SEMICOLON,
    LPAREN,
    RPAREN,
    CARET,

    /** {@code -} not immediately followed by an unquoted-continuation character. */
    MINUS,

    /** End of input. */
    EOF
}
