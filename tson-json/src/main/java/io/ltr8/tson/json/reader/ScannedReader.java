package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;

/**
 * A reader for a record-shaped position that can take the reserved-member scan from the dispatcher that
 * reached it, rather than making its own.
 *
 * <p><b>One scan per object, whoever makes it.</b> §6.1.6 gives member order no meaning, so recognising
 * [TSON-JSON] §3.3's annotation object costs a lookahead over the whole object ({@link ReservedMembers}). A
 * dispatcher has to make it to choose a reader at all; a reader it chose that scanned again would pay twice
 * for one answer. A reader reached with no dispatcher in front of it scans for itself through {@link
 * io.ltr8.tson.json.JsonTypeReader#read}.
 */
interface ScannedReader {

    /**
     * Reads the value at {@code ctx}'s cursor, {@code tag} being the scan of it already made. Where the value
     * is not an object, {@code tag} is {@link ReservedMembers.Tag#NONE}. The dispatcher has already refused
     * a misused reserved member, so what reaches here is a tag to honour or restate.
     */
    Object readScanned(JsonReadContext ctx, ReservedMembers.Tag tag);
}
