package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.stream.JsonEvent;

/**
 * Discards one JSON value whose opening event has already been pulled -- what a reader does after reporting
 * a problem it cannot read past, so the read continues at the next sibling rather than ending.
 *
 * <p>That is what makes collecting mode worth having: a value nothing could read costs a verdict on itself
 * and nothing on anything else.
 *
 * <p><b>Iterative over its own depth rather than recursive.</b> This walks values nothing keeps, so it is
 * the one place a document's nesting would cost stack for no result -- [TSON-JSON] §10.1's bound has already
 * refused anything deeper than the reader reads, and a skip that recursed would spend the stack twice for a
 * value being thrown away.
 */
final class JsonEventSkip {

    private JsonEventSkip() {
    }

    /** Consumes the rest of the value {@code first} opened; a no-op when {@code first} was a scalar. */
    static void value(JsonReadContext ctx, JsonEvent first) {
        int depth = first instanceof JsonEvent.ObjectStart || first instanceof JsonEvent.ArrayStart ? 1 : 0;
        while (depth > 0) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectStart || event instanceof JsonEvent.ArrayStart) {
                depth++;
            } else if (event instanceof JsonEvent.ObjectEnd || event instanceof JsonEvent.ArrayEnd) {
                depth--;
            }
        }
    }

    /** Consumes one whole value from {@code ctx}'s cursor, opening event included. */
    static void nextValue(JsonReadContext ctx) {
        value(ctx, ctx.next());
    }
}
