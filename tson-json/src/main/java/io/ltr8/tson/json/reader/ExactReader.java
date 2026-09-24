package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;

/**
 * The reader for exactly one record type, which a dispatcher reaches once it has placed the value -- and
 * which reads the object itself, its leading {@code $type} included, with no lookahead.
 *
 * <p><b>Why a dispatcher hands it a second reader.</b> §3.3's wrapper form puts the value in {@code $value}
 * and names its type in the leading {@code $type}; the value inside may carry a tag of its own, so it is read
 * at the type's <em>entry</em> reader, which dispatches again where the type has subtypes. The exact reader
 * cannot know its entry -- an OPEN record with subtypes has a dispatcher in front of it -- so whoever reached
 * it says ({@link Route}).
 *
 * <p>A dispatcher implements it too, for the case where a tag names a base that dispatches its own family:
 * it reads the object again from its leading members, which is a bounded peek and never a scan.
 */
interface ExactReader {

    /**
     * Reads the value at {@code ctx}'s cursor as exactly this reader's type.
     *
     * @param wrapped the reader for a wrapper's {@code $value}, should the object turn out to be one
     */
    Object readExact(JsonReadContext ctx, JsonTypeReader<?> wrapped);
}
