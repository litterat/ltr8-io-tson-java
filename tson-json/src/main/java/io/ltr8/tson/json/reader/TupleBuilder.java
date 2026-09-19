package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;

import java.util.List;

/**
 * What a read mode makes of a tuple's positions -- the one per-tuple step that differs by mode ({@link
 * TreeTupleBuilder}, {@link BindTupleBuilder}). {@link TupleReader} applies every rule and calls this once.
 */
interface TupleBuilder {

    /**
     * The tuple, from its positions in order: each a child's value or a {@link Slots} marker. A document longer
     * than the arity leaves {@link Slots#REFUSED} for each extra element, and one shorter leaves fewer positions;
     * both were reported, so {@code clean} is false.
     */
    Object build(JsonReadContext ctx, List<Object> positions, boolean clean);

    /** What stands where the tuple itself was refused. */
    Object refused();
}
