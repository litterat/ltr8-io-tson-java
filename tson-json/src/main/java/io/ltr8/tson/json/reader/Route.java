package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;

/**
 * Where a dispatcher sends a value whose {@code $type} it has matched: two readers, both wired when the schema
 * compiles, so a read never looks a name up.
 *
 * <p><b>Two because §3.3's two forms want two different things.</b> In the <b>inline</b> form the object is
 * the selected type's own value, so it goes to the reader that reads exactly that type -- through no further
 * dispatch, which is the dispatch having been flattened at compile ({@link #exact}). In the <b>wrapper</b>
 * form the tag names the type of {@code $value}, and that value may carry a tag of its own: it is read at
 * the type's entry reader, which dispatches again if the type has subtypes.
 *
 * @param entry  the selected type's compiled reader, whatever it is -- what reads a wrapper's {@code $value}
 * @param inline the reader for exactly the selected type -- what reads the object itself
 */
record Route(JsonTypeReader<?> entry, JsonTypeReader<?> inline) {

    /** The route to a type whose compiled reader is {@code entry}. */
    static Route to(JsonTypeReader<?> entry) {
        return new Route(entry, exact(entry));
    }

    /**
     * The reader that reads exactly the type {@code entry} was compiled for. An OPEN record with subtypes
     * compiles to a dispatcher in front of its own concrete reader, and a tag naming that record wants the
     * concrete one; every other reader already reads exactly its type -- an ABSTRACT or SEALED base included,
     * since naming one selects nothing and its dispatcher is what says so.
     */
    static JsonTypeReader<?> exact(JsonTypeReader<?> entry) {
        return entry instanceof DispatchTagReader dispatch && dispatch.untagged() != null
                ? dispatch.untagged()
                : entry;
    }

    /**
     * Reads the tagged value at {@code ctx}'s cursor. A wrapper's {@code $value} is read at {@link #entry}; an
     * inline object goes to the exact reader, and where there is none -- a type that does not read an object
     * as its own value -- the object can only be a wrapper, and is read as one, refusing what it is not.
     */
    Object read(JsonReadContext ctx, ReservedMembers.Lead lead) {
        if (lead.wrapper() || !(inline instanceof ExactReader exact)) {
            return ReservedMembers.readWrapped(ctx, entry);
        }
        return exact.readExact(ctx, entry);
    }
}
