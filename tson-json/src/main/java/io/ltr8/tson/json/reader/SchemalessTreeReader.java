package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonBoolean;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonNumber;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonString;
import io.ltr8.tson.json.tree.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reduces an event source into a {@link JsonValue} tree -- the engine {@code JsonTreeReader} is a facade
 * over, and the peer of {@link DataClassObjectReader} under the same front door.
 *
 * <p><b>Genuinely schemaless</b>, unlike its object-shaped sibling. A {@code DataClassObjectReader} is
 * driven by the target class, which is in effect its schema; this is driven by nothing at all -- the wire
 * structure is the whole of the source of truth -- so the word is accurate here where it would not have
 * been there. {@code tson-compiler}'s tree reader carries the same name for the same reason.
 *
 * <p><b>Recursive over containers, and safe to be</b> because {@code JsonStream} refuses a document past
 * [TSON-JSON] §10.1's nesting bound before this descends into it. That is why the bound is counted where
 * containers open rather than here.
 *
 * <p><b>Frame-free.</b> Draining the source through {@link JsonEvent.EndOfDocument} -- which is what
 * rejects trailing content -- belongs to whoever owns the document, which is the facade.
 *
 * <p><b>Tree mode keeps what it built.</b> A collecting read that found problems still hands back the tree,
 * where {@code DataClassObjectReader} hands back nothing: a {@code JsonObject} has somewhere to put a
 * partial answer and a Java record does not. That asymmetry is deliberate and is the one
 * {@code tson-compiler} already draws between its own two readers.
 */
public final class SchemalessTreeReader {

    /**
     * Reads one value at {@code ctx}'s current position, {@code first} being its opening event, already
     * pulled.
     *
     * <p>The general form, for a caller managing their own context. The facade's whole-document entry
     * points are this plus framing.
     */
    public JsonValue read(JsonReadContext ctx, JsonEvent first) {
        return switch (first) {
            case JsonEvent.StringValue string -> new JsonString(string.value());
            case JsonEvent.NumberValue number -> new JsonNumber(number.literal());
            case JsonEvent.BooleanValue bool -> JsonBoolean.of(bool.value());
            case JsonEvent.NullValue ignored -> JsonNull.INSTANCE;
            case JsonEvent.ObjectStart ignored -> readObject(ctx);
            case JsonEvent.ArrayStart ignored -> readArray(ctx);
            default -> throw new IllegalStateException("a value was due and the stream produced " + first);
        };
    }

    /** Reads one value, pulling its opening event first. */
    public JsonValue read(JsonReadContext ctx) {
        return read(ctx, ctx.next());
    }

    /**
     * <p><b>Where §3.1's duplicate-member rule lands.</b> A repeated name is reported and the later value
     * wins, rather than thrown: a collecting read finds every repeat in one pass, which is the whole reason
     * a read has a receiver, and a fail-fast one still stops at the first because its receiver throws.
     *
     * <p>It is a {@code DUPLICATE_FIELD} diagnostic rather than a parse failure because the grammar accepts
     * the document -- §3.1 puts a repeat in the categories that follow the position's type, not in the parse
     * category. JEP 540 calls it a parse error for want of anywhere else to put it; this has somewhere.
     */
    private JsonValue readObject(JsonReadContext ctx) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                return new JsonObject(members);
            }
            if (!(event instanceof JsonEvent.MemberName name)) {
                throw new IllegalStateException("a member name or '}' was due and the stream produced " + event);
            }
            JsonReadContext at = ctx.field(name.name());
            JsonValue value = read(at, at.next());
            if (members.put(name.name(), value) != null) {
                at.report(Diagnostic.Code.DUPLICATE_FIELD,
                        "'%s' is already a member of this object, and a member name appears once"
                                .formatted(name.name()),
                        "each member stated once", "'" + name.name() + "' stated again");
            }
        }
    }

    private JsonValue readArray(JsonReadContext ctx) {
        List<JsonValue> elements = new ArrayList<>();
        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ArrayEnd) {
                return new JsonArray(elements);
            }
            elements.add(read(ctx.index(elements.size()), event));
        }
    }
}
