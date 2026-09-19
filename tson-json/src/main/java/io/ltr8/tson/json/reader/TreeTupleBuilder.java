package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;

import java.util.List;

/**
 * Tree mode's tuple: a {@link JsonArray} of the positions the document stated, an absent or refused position --
 * and an element past the arity -- standing as {@link JsonNull} in its slot. Tree mode keeps what it built.
 */
final class TreeTupleBuilder implements TupleBuilder {

    static final TreeTupleBuilder INSTANCE = new TreeTupleBuilder();

    /** Tree mode's tuple reader: each position read at its element type's own reader. */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        TuplePlan plan = TuplePlan.of(name, definition, context);
        return new TupleReader(plan, plan.schemaSlots(), INSTANCE);
    };

    private TreeTupleBuilder() {
    }

    @Override
    public Object build(JsonReadContext ctx, List<Object> positions, boolean clean) {
        for (int i = 0; i < positions.size(); i++) {
            Object position = positions.get(i);
            if (position == Slots.ABSENT || position == Slots.REFUSED) {
                positions.set(i, JsonNull.INSTANCE);
            }
        }
        @SuppressWarnings("unchecked")   // every slot now holds a node: a child's, or the placeholder just set
        List<JsonValue> nodes = (List<JsonValue>) (List<?>) positions;
        return new JsonArray(nodes);
    }

    @Override
    public Object refused() {
        return JsonNull.INSTANCE;
    }
}
