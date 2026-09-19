package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonValue;

/**
 * Tree mode's atom as a set's element: the node {@link TreeAtomReader} would yield, with the parsed value's
 * {@link ValueIdentity} beside it ({@link ValueIdentity.Identified}), which {@link ArrayReader} judges duplicates
 * on and unwraps.
 *
 * <p>[TSON-SCHEMA] §7.5 judges a set's duplicates on the element's value space, and a node holds only its
 * spelling -- two spellings of one instant are two nodes and one element. Only a set's atom elements are read
 * here ({@link TreeAtomReader#keyed}), so every other atom position keeps a reader that discards the parse.
 */
final class TreeAtomKeyedReader implements JsonTypeReader<Object> {

    private final JsonTypeReader<?> delegate;

    TreeAtomKeyedReader(JsonTypeReader<?> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object read(JsonReadContext ctx) {
        JsonValue node = Nodes.scalar(ctx.peek());
        int before = ctx.reported();
        Object value = delegate.read(ctx);
        // As TreeAtomReader: a refused or composite value has only the placeholder, and no identity to judge.
        if (node == null || ctx.reported() > before) {
            return JsonNull.INSTANCE;
        }
        return new ValueIdentity.Identified(node, ValueIdentity.of(value));
    }
}
