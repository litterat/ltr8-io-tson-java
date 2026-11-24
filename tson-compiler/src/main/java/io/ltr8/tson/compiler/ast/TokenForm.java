package io.ltr8.tson.compiler.ast;

/**
 * The three token forms of [TSON-DATA] §2.4, a token being "text plus form": {@code 42} and {@code "42"} are one text
 * in two forms, which base type resolution (§4) reads as a number and a string.
 */
public enum TokenForm {
    UNQUOTED,
    SINGLE_LINE_QUOTED,
    MULTI_LINE_QUOTED
}
