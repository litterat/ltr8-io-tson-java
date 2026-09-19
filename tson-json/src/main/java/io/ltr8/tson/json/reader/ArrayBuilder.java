package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;

import java.util.List;

/**
 * What a read mode makes of an array's elements -- the one per-array step that differs by mode ({@link
 * TreeArrayBuilder}, {@link BindArrayBuilder}). {@link ArrayReader} applies every rule and calls this once.
 */
interface ArrayBuilder {

    /**
     * The array, from its elements in order: each a child's value or a {@link Slots} marker -- {@link
     * Slots#ABSENT} for an absent element, {@link Slots#REFUSED} for a refused one.
     *
     * @param clean whether nothing was reported while the array was read
     */
    Object build(JsonReadContext ctx, List<Object> elements, boolean clean);

    /** What stands where the array itself was refused. */
    Object refused();
}
