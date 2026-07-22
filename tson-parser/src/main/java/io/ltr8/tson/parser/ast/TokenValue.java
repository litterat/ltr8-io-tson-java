package io.ltr8.tson.parser.ast;

/**
 * A leaf {@code token} core-value (§2.4, §7.4): {@code text} is the token's decoded content
 * (escape-processed, and whitespace-stripped for multi-line tokens — exactly what
 * {@code io.ltr8.tson.parser.lexer.Token#text()} already provides), unresolved and
 * uninterpreted. {@code form} records which of the three token kinds produced it.
 */
public record TokenValue(String text, TokenForm form) implements CoreValue {
}
