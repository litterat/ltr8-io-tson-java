package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Tree mode's map, in the form the document was written in: a {@link JsonObject} keyed by the member names for the
 * object form, a {@link JsonArray} of two-element arrays for the pairs form. An absent or refused value stands as
 * {@link JsonNull}, as does a refused compound key; tree mode keeps what it built.
 */
final class TreeMapBuilder implements MapBuilder {

    static final TreeMapBuilder INSTANCE = new TreeMapBuilder();

    /** Tree mode's map reader, in the form {@code K} selects ({@link MapPlan}). */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        MapPlan plan = MapPlan.of(name, definition, context);
        return plan.objectForm()
                ? new MapObjectReader(plan, plan.keyParser(), null, plan.schemaValue(), INSTANCE)
                : new MapPairsReader(plan, plan.schemaKey(), plan.schemaValue(), INSTANCE);
    };

    private TreeMapBuilder() {
    }

    @Override
    public Object build(JsonReadContext ctx, List<String> names, List<Object> keys, List<Object> values,
                        boolean clean) {
        if (names != null) {
            JsonObject.Builder members = JsonObject.builder(names.size());
            for (int i = 0; i < names.size(); i++) {
                members.put(names.get(i), node(values.get(i)));
            }
            return members.build();
        }
        List<JsonValue> pairs = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            pairs.add(new JsonArray(List.of(node(keys.get(i)), node(values.get(i)))));
        }
        return new JsonArray(pairs);
    }

    @Override
    public Object refused() {
        return JsonNull.INSTANCE;
    }

    private static JsonValue node(Object slot) {
        return slot == Slots.ABSENT || slot == Slots.REFUSED ? JsonNull.INSTANCE : (JsonValue) slot;
    }
}
