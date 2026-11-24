package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * How a composite reader reaches the reader for one of its children -- the seam that lets a compile wire real object
 * references rather than leaving every position to consult a name-keyed map on every read. A name is an entry's
 * reader; a type written in place ({@link #resolveInline}) has a reader built from its body.
 */
@FunctionalInterface
public interface TypeReaderResolver {

    /** The reader of the entry {@code name}. */
    JsonTypeReader<?> resolve(String name);

    /** The reader of what a type slot holds: the named entry's, or the one built from a type written in place. */
    default JsonTypeReader<?> resolve(TypeExpr type) {
        return switch (type) {
            case TypeRef reference -> resolve(reference.name());
            case TypeInline inline -> resolveInline(inline);
        };
    }

    /**
     * The reader built from a type written in place. A resolver over entry names alone holds no compilation to build
     * one from, so reaching it with an inline type is a caller wiring it where a compiled schema's resolver belongs.
     */
    default JsonTypeReader<?> resolveInline(TypeInline type) {
        throw new IllegalStateException("this resolver resolves entry names only, and was asked for the reader of "
                + "the type written in place '" + type.text() + "' -- an inline type's reader is built by the "
                + "compilation that holds the slot");
    }
}
