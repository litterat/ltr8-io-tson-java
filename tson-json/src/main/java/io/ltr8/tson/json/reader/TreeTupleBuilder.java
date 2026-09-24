package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;

import java.util.List;

/**
 * Tree mode's tuple: a {@link JsonArray} of the positions the document stated, an absent position standing as
 * {@link JsonNull} in its slot. A tuple whose read reported anything builds nothing -- all-or-nothing, as bind
 * mode is.
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
        if (!clean) {
            return null;
        }
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i) == Slots.ABSENT) {
                positions.set(i, JsonNull.INSTANCE);
            }
        }
        @SuppressWarnings("unchecked")   // every slot now holds a node: a child's, or the absence just set
        List<JsonValue> nodes = (List<JsonValue>) (List<?>) positions;
        return new JsonArray(nodes);
    }

    @Override
    public Object refused() {
        return null;
    }
}
