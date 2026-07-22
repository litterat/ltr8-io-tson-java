package io.ltr8.tson.parser.lexer;

/**
 * A single lexical token.
 *
 * <p>{@code text} is the token's logical content: for {@link TokenType#SINGLE_LINE_STRING}
 * and {@link TokenType#MULTI_LINE_STRING} this is the decoded value — escape
 * sequences resolved and, for multi-line tokens, common indentation stripped
 * (§2.4, §7.2.2, §7.2.3). For every other token kind, {@code text} is the
 * exact source lexeme (an unquoted token is stored exactly as written, which
 * is what base type resolution and numeric-representation preservation
 * require, §4.3).
 */
public record Token(TokenType type, String text, Position start, Position end) {
}
