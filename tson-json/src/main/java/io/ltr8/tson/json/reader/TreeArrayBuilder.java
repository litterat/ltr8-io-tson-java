package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;

import java.util.List;

/**
 * Tree mode's array: a {@link JsonArray} of the elements the document stated, an absent element standing as
 * {@link JsonNull} in its slot, the value itself (§7). An array whose read reported anything builds nothing --
 * all-or-nothing, as bind mode is.
 */
final class TreeArrayBuilder implements ArrayBuilder {

    static final TreeArrayBuilder INSTANCE = new TreeArrayBuilder();

    /**
     * Tree mode's array and set reader: elements read at the element type's own reader, a set's atom elements
     * carrying the identity its duplicates are judged on ({@link TreeAtomReader#keyed}).
     */
    static final ValueReaderFactory FACTORY = (name, definition, context) -> {
        ArrayPlan plan = ArrayPlan.of(name, definition, context);
        JsonTypeReader<?> element = plan.unique() ? TreeAtomReader.keyed(plan.schemaElement()) : plan.schemaElement();
        return new ArrayReader(plan, element, INSTANCE);
    };

    private TreeArrayBuilder() {
    }

    @Override
    public Object build(JsonReadContext ctx, List<Object> elements, boolean clean) {
        if (!clean) {
            return null;
        }
        for (int i = 0; i < elements.size(); i++) {
            if (elements.get(i) == Slots.ABSENT) {
                elements.set(i, JsonNull.INSTANCE);
            }
        }
        @SuppressWarnings("unchecked")   // every slot now holds a node: a child's, or the absence just set
        List<JsonValue> nodes = (List<JsonValue>) (List<?>) elements;
        return new JsonArray(nodes);
    }

    @Override
    public Object refused() {
        return null;
    }
}
