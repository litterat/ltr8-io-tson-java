package io.ltr8.tson.json.lexer;

import io.ltr8.tson.json.JsonPosition;

/**
 * One lexical token, as a retainable snapshot.
 *
 * <p>{@code text} is the token's logical content: a {@link JsonTokenType#STRING}'s decoded value,
 * escapes resolved; a {@link JsonTokenType#NUMBER}'s <em>exact source lexeme</em>, because
 * [TSON-JSON] §5.3 preserves an exact number's digits and scale and §3.1 forbids rounding one
 * silently -- so the digits have to survive the lexer to be preserved anywhere. Every other kind
 * carries its own spelling, and {@link JsonTokenType#EOF} carries the empty string.
 *
 * <p>Position is stored as six raw {@code int}s rather than two nested {@link JsonPosition} objects,
 * so a caller that walks tokens without retaining a position never allocates one --
 * {@link #start()}/{@link #end()} materialize on demand. {@code JsonLexer} itself hands back none of
 * these at all on its hot path; see its own Javadoc.
 */
public record JsonToken(JsonTokenType type, String text, int startLine, int startColumn, int startByteOffset,
                        int endLine, int endColumn, int endByteOffset) {

    /** Materializes this token's start position -- a fresh {@link JsonPosition}, built only when actually called. */
    public JsonPosition start() {
        return new JsonPosition(startLine, startColumn, startByteOffset);
    }

    /** Materializes this token's end position -- a fresh {@link JsonPosition}, built only when actually called. */
    public JsonPosition end() {
        return new JsonPosition(endLine, endColumn, endByteOffset);
    }
}
