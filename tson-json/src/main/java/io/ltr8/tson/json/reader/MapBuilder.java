package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;

import java.util.List;

/**
 * What a read mode makes of a map's entries -- the one per-map step that differs by mode ({@link TreeMapBuilder},
 * {@link BindMapBuilder}). The form's loop applies every rule, resolves a repeated key to one entry, and calls
 * this once.
 */
interface MapBuilder {

    /**
     * The map, from its entries in order: each key as the loop decoded it, each value a child's value or a
     * {@link Slots} marker ({@link Slots#ABSENT} for an entry with an absent value, {@link Slots#REFUSED} for a
     * refused one).
     *
     * @param names the member name each key was written as, in the object form; null in the pairs form
     * @param clean whether nothing was reported while the map was read
     */
    Object build(JsonReadContext ctx, List<String> names, List<Object> keys, List<Object> values, boolean clean);

    /** What stands where the map itself was refused. */
    Object refused();
}
