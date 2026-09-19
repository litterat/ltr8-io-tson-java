package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;

/**
 * What a read mode makes of a record's filled slots -- the one per-record step of a record read that differs by
 * mode ({@link TreeRecordBuilder}, {@link BindRecordBuilder}). {@link RecordReader} applies every rule and calls
 * this once, at the end; nothing on the per-field path does.
 *
 * <p>The other thing a mode decides -- which reader each field is read at, and the form a schema-stated value
 * takes -- is decided once, by the mode's factory, before the reader exists.
 */
interface RecordBuilder {

    /**
     * The record, from one slot per field in declaration order: null where the document did not state the field
     * and nothing was injected, otherwise a child's value, an injected one, or one of {@link RecordReader}'s
     * markers.
     *
     * @param ctx   where the record was read, for a mode whose building can itself be refused -- a bound
     *              class's constructor rejecting the values
     * @param clean whether nothing was reported while this record was read -- a mode that builds only from a
     *              valid value declines here, rather than asking every field
     */
    Object build(JsonReadContext ctx, Object[] slots, boolean clean);

    /** What stands where this record's value was refused before any slot was filled. */
    Object refused();
}
