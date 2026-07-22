package io.ltr8.tson.parser.ast;

/**
 * The three token forms of §2.4: "text plus form". Form is consulted only by base type
 * resolution (§4, not yet implemented) and is otherwise not meaning — kept here purely so a
 * later layer can tell {@code 42} (unquoted) from {@code "42"} (quoted).
 */
public enum TokenForm {
    UNQUOTED,
    SINGLE_LINE_QUOTED,
    MULTI_LINE_QUOTED
}
