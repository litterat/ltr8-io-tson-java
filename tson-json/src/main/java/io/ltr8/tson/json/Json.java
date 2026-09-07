package io.ltr8.tson.json;

import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.base.LimitsPolicy;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonEventSource;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonBoolean;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonNumber;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonString;
import io.ltr8.tson.json.tree.JsonValue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The front door of the JSON stack: parse a document into a {@link JsonValue} tree, and render one
 * back.
 *
 * <p>JEP 540's entry points, with one addition §3.1 requires. {@link #parse(String)} matches the JDK's;
 * {@link #parse(InputStream)} is this module's own, because [TSON-JSON] §3.1 makes the document UTF-8
 * and puts a byte offset in every error report — facts a decoder handed an already-decoded
 * {@code String} has already lost. A caller with bytes should hand over the bytes.
 *
 * <p><b>Where §3.1's duplicate-member rule lands.</b> A repeated member name is refused here, and not
 * in the event stream below: the stream is grammar and a repeat is not a grammar error. §3.1 gives its
 * <em>category</em> to the position's type, which no schemaless parse has — so this reports it as JEP
 * 540 does, an unconditional parse failure at the repeated occurrence, and the schema-directed decode
 * of §5–§8 will report the same fact with the category its position gives it.
 *
 * <p><b>What a parsed tree is not.</b> It is a JSON document, not a TSON value: no schema has been
 * consulted, {@code null} is a value ({@link JsonNull}), and no member name has been read as a field
 * name. §1.3's principle 1 makes decoding schema-directed, so this is a JSON convenience the encoding
 * itself does not define — useful, and never a validation.
 */
public final class Json {

    private Json() {
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    /** @throws ParseException if the document is not JSON within §3.1's profile */
    public static JsonValue parse(String source) {
        return parse(new JsonStream(source));
    }

    /** @throws ParseException if the document is not JSON within §3.1's profile */
    public static JsonValue parse(String source, int maxDepth) {
        return parse(new JsonStream(source, maxDepth));
    }

    /**
     * Off UTF-8 bytes, which is where §3.1's rules actually bite: invalid sequences are refused rather
     * than replaced, and every position carries a real byte offset. {@code source} is not closed here.
     */
    public static JsonValue parse(InputStream source) {
        return parse(new JsonStream(source));
    }

    /** {@link #parse(InputStream)} under a nesting bound other than {@link LimitsPolicy#DEFAULT_MAX_DEPTH the processor's default}. */
    public static JsonValue parse(InputStream source, int maxDepth) {
        return parse(new JsonStream(source, maxDepth));
    }

    /**
     * Reduces an event source into a tree — the seam a caller with events already in hand comes
     * through, and what the two {@code parse} overloads are built on.
     *
     * <p>Recursive over containers, and safe to be because {@link JsonStream} refuses a document past
     * §10.1's nesting bound <em>before</em> this descends — the reason that bound is counted where every
     * container opens rather than here. A synthetic source that does not enforce one owes its own.
     *
     * <p>The stream is drained through {@link JsonEvent.EndOfDocument}, which is what rejects trailing
     * content: stopping at the root value's last event would read {@code "[1] 2"} clean.
     */
    public static JsonValue parse(JsonEventSource events) {
        JsonValue root = readValue(events, events.next());
        if (!(events.next() instanceof JsonEvent.EndOfDocument)) {
            throw new IllegalStateException("the stream produced events after the document's root value");
        }
        return root;
    }

    /** One value, {@code first} being its opening event, already pulled. */
    private static JsonValue readValue(JsonEventSource events, JsonEvent first) {
        return switch (first) {
            case JsonEvent.StringValue string -> new JsonString(string.value());
            case JsonEvent.NumberValue number -> new JsonNumber(number.literal());
            case JsonEvent.BooleanValue bool -> JsonBoolean.of(bool.value());
            case JsonEvent.NullValue ignored -> JsonNull.INSTANCE;
            case JsonEvent.ObjectStart ignored -> readObject(events);
            case JsonEvent.ArrayStart ignored -> readArray(events);
            default -> throw new IllegalStateException("a value was due and the stream produced " + first);
        };
    }

    private static JsonValue readObject(JsonEventSource events) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        while (true) {
            JsonEvent event = events.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                return new JsonObject(members);
            }
            if (!(event instanceof JsonEvent.MemberName name)) {
                throw new IllegalStateException("a member name or '}' was due and the stream produced " + event);
            }
            JsonValue value = readValue(events, events.next());
            if (members.putIfAbsent(name.name(), value) != null) {
                // §3.1, and JEP 540 for the same reason: RFC 8259's "SHOULD be unique" leaves an object
                // whose meaning depends on which member a reader kept, and §10.2 makes the divergence an
                // attack -- two `$type` members, one seen by a filter and the other by the decoder.
                throw new ParseException(
                        "'%s' is already a member of this object, and a member name appears once"
                                .formatted(name.name()), name.position());
            }
        }
    }

    private static JsonValue readArray(JsonEventSource events) {
        List<JsonValue> elements = new ArrayList<>();
        while (true) {
            JsonEvent event = events.next();
            if (event instanceof JsonEvent.ArrayEnd) {
                return new JsonArray(elements);
            }
            elements.add(readValue(events, event));
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────

    /**
     * A pretty-printed rendering, one member or element per line, indented by {@code indent} per level
     * -- JEP 540's spelling of {@link JsonValue#toDisplayString(String)}, which is where the work lives
     * because that is where the string quoting does.
     */
    public static String toDisplayString(JsonValue value, String indent) {
        return value.toDisplayString(indent);
    }

    /** {@link #toDisplayString(JsonValue, String)} at two spaces, the shape most JSON is read in. */
    public static String toDisplayString(JsonValue value) {
        return value.toDisplayString();
    }
}
