package io.ltr8.tson.compiler.lexer;

import io.ltr8.tson.compiler.Position;

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
 *
 * <p>Position is stored as six raw {@code int} fields rather than two nested {@link Position}
 * objects — {@link #start()}/{@link #end()} materialize a {@link Position} on demand, so a caller
 * that only ever compares two tokens' adjacency (via {@link #adjacentTo}) or a token's start
 * against a remembered coordinate (via {@link #startsAt}) never allocates one at all. A high-volume
 * read walks far more tokens than it ever needs to retain a {@link Position} for — most positions
 * are used once, for an adjacency check, and discarded; only a token that becomes part of a
 * retained {@code TsonEvent} (or an error) needs a real {@link Position} object.
 */
public record Token(TokenType type, String text, int startLine, int startColumn, int startByteOffset,
                     int endLine, int endColumn, int endByteOffset) {

    /** Materializes this token's start position — a fresh {@link Position}, built only when actually called. */
    public Position start() {
        return new Position(startLine, startColumn, startByteOffset);
    }

    /** Materializes this token's end position — a fresh {@link Position}, built only when actually called. */
    public Position end() {
        return new Position(endLine, endColumn, endByteOffset);
    }

    /** Whether this token's end is exactly where {@code other} starts — compared as raw coordinates, no {@link Position} allocated. */
    public boolean adjacentTo(Token other) {
        return endLine == other.startLine && endColumn == other.startColumn && endByteOffset == other.startByteOffset;
    }

    /** Whether this token starts exactly at the given raw coordinates — no {@link Position} allocated. */
    public boolean startsAt(int line, int column, int byteOffset) {
        return startLine == line && startColumn == column && startByteOffset == byteOffset;
    }
}
