package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.meta.MapBody;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [TSON-JSON] §6.5's <b>pairs form</b>: a map whose key type is a record, tuple, array, map or choice -- the
 * compound keys [TSON-DATA] §2.6 admits, which a JSON object's string-only member names cannot spell. The map
 * is a JSON array of two-element arrays, which is the second of §8.3's class-stability leaks: a brace-class
 * type wearing bracket clothing.
 *
 * <p>A compound key compares as a value too, through {@link JsonValueIdentity}'s recursive reduction -- member
 * order carries no meaning (§6.1.6) and a {@code JsonNumber} keeps its literal, so two objects §5.3 makes one
 * value would otherwise compare unequal.
 */
final class JsonPairsMapReader extends JsonMapTreeReader {

    private final JsonTypeReader<?> keyReader;

    JsonPairsMapReader(String name, MapBody body, JsonTypeReader<?> keyReader, JsonTypeReader<?> value,
                       JsonSchemaLocation schemaLocation) {
        super(name, body, value, schemaLocation);
        this.keyReader = keyReader;
    }

    @Override
    JsonValue readEntries(JsonReadContext ctx) {
        JsonEvent first = ctx.next();
        if (!(first instanceof JsonEvent.ArrayStart)) {
            return wrongShape(ctx, first, "a JSON array of two-element arrays");
        }
        List<JsonValue> entries = new ArrayList<>();
        Map<Object, Integer> byIdentity = new HashMap<>();
        int count = 0;
        while (!(ctx.peek() instanceof JsonEvent.ArrayEnd)) {
            JsonReadContext at = ctx.index(count);
            JsonValue pair = readPair(at, byIdentity, count++);
            if (pair != null) {
                entries.add(pair);
            }
        }
        ctx.next();   // ArrayEnd
        checkSize(ctx, count);
        return new JsonArray(entries);
    }

    /** One {@code [k, v]}. §6.5: an element that is not a two-element array is a validation error. */
    private JsonValue readPair(JsonReadContext at, Map<Object, Integer> byIdentity, int index) {
        JsonEvent opening = at.next();
        if (!(opening instanceof JsonEvent.ArrayStart)) {
            at.report(Diagnostic.Code.TYPE_MISMATCH,
                    "'%s' is in pairs form, whose every element is a two-element array, and this is %s"
                            .formatted(name, JsonAtoms.describe(opening)),
                    "a two-element array", JsonAtoms.describe(opening));
            JsonEventSkip.value(at, opening);
            return null;
        }
        if (at.peek() instanceof JsonEvent.ArrayEnd) {
            at.next();
            at.report(Diagnostic.Code.WRONG_ARITY, "'%s' is in pairs form and this entry has no key".formatted(name),
                    "a two-element array", "an empty array");
            return null;
        }
        int before = at.reported();
        JsonValue key = (JsonValue) keyReader.read(at.index(0));
        if (at.peek() instanceof JsonEvent.ArrayEnd) {
            at.next();
            at.report(Diagnostic.Code.WRONG_ARITY, "'%s' is in pairs form and this entry has no value"
                    .formatted(name), "a two-element array", "a one-element array");
            return null;
        }
        JsonValue entry = entryValue(at.index(1), String.valueOf(index));
        if (at.reported() == before && byIdentity.putIfAbsent(JsonValueIdentity.of(key), index) != null) {
            at.report(Diagnostic.Code.DUPLICATE_MAP_KEY,
                    "duplicate key in '%s' -- a map states each key at most once, and the repeat states an entry "
                            .formatted(name) + "for nothing",
                    "each key stated once", String.valueOf(key));
        }
        if (!(at.peek() instanceof JsonEvent.ArrayEnd)) {
            // More than two elements. One diagnostic for the entry, then the rest discarded: there is no
            // position for any of them to be read at, and one malformed entry is one problem.
            at.report(Diagnostic.Code.WRONG_ARITY, "'%s' is in pairs form and this entry has more than a key and "
                    .formatted(name) + "a value", "a two-element array", "a longer array");
            while (!(at.peek() instanceof JsonEvent.ArrayEnd)) {
                JsonEventSkip.nextValue(at);
            }
        }
        at.next();   // the pair's own ArrayEnd
        return new JsonArray(List.of(key, entry));
    }
}
