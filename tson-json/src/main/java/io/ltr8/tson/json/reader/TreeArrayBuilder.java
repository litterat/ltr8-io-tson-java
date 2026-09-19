package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;

import java.util.List;

/**
 * Tree mode's array: a {@link JsonArray} of the elements the document stated, an absent or refused element
 * standing as {@link JsonNull} in its slot -- the first being the value itself (§7), the second the placeholder a
 * diagnostic beside it explains. Tree mode keeps what it built.
 */
final class TreeArrayBuilder implements ArrayBuilder {

    static final TreeArrayBuilder INSTANCE = new TreeArrayBuilder();

    /** Tree mode's array and set reader: elements read at the element type's own reader. */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        ArrayPlan plan = ArrayPlan.of(name, definition, context);
        return new ArrayReader(plan, plan.schemaElement(), INSTANCE);
    };

    private TreeArrayBuilder() {
    }

    @Override
    public Object build(JsonReadContext ctx, List<Object> elements, boolean clean) {
        for (int i = 0; i < elements.size(); i++) {
            Object element = elements.get(i);
            if (element == Slots.ABSENT || element == Slots.REFUSED) {
                elements.set(i, JsonNull.INSTANCE);
            }
        }
        @SuppressWarnings("unchecked")   // every slot now holds a node: a child's, or the placeholder just set
        List<JsonValue> nodes = (List<JsonValue>) (List<?>) elements;
        return new JsonArray(nodes);
    }

    @Override
    public Object refused() {
        return JsonNull.INSTANCE;
    }
}
