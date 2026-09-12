package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonBoolean;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonNumber;
import io.ltr8.tson.json.tree.JsonString;
import io.ltr8.tson.json.tree.JsonValue;

/**
 * One scalar event as the tree node it stands for. The composite kinds are each reader's own to assemble, so
 * this covers exactly the four leaves and null.
 *
 * <p>A number keeps its <b>literal</b> rather than becoming a host numeric type: §3.1 requires a decoder
 * preserve a number's digits and §5.3 preserves an exact value's digits and scale, so {@code 199.90} must
 * come back out as it went in.
 */
final class Nodes {

    private Nodes() {
    }

    /**
     * A node as its <b>value</b>, for a diagnostic that talks about values rather than about JSON.
     *
     * <p>A {@code JsonString} renders as its content and not as the quoted JSON spelling, because a message
     * saying an element "appears more than once" is naming the value that repeated, and the schema's
     * vernacular is what those messages are written in ({@code base.diagnostics}). The TSON reader's
     * {@code Rendered.value} answers the same question from a host value; this answers it from a node,
     * because tree mode discards the host value by design.
     */
    static String rendered(JsonValue value) {
        return value instanceof JsonString string ? string.value() : String.valueOf(value);
    }

    /** The node {@code event} carries, or null when {@code event} opens a composite or ends one. */
    static JsonValue scalar(JsonEvent event) {
        return switch (event) {
            case JsonEvent.StringValue string -> new JsonString(string.value());
            case JsonEvent.NumberValue number -> new JsonNumber(number.literal());
            case JsonEvent.BooleanValue bool -> JsonBoolean.of(bool.value());
            case JsonEvent.NullValue ignored -> JsonNull.INSTANCE;
            default -> null;
        };
    }
}
