package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;

/**
 * The meta-kernel's {@code tuple_element} record (Part 2 §5.3, §8.1): one position of a resolved
 * {@link TupleBody}. {@code voidable} says whether the void sentinel {@code _} may stand in the position, the fact
 * an array element and a map value carry; a position is never left out. Bound through plain generic binding it
 * always appears in output, even at its default {@code false}.
 */
public record TupleElement(@Field("element_type") TypeRef elementType, boolean voidable) {

    /** A position the void sentinel may not stand in. */
    public static TupleElement required(TypeRef elementType) {
        return new TupleElement(elementType, false);
    }
}
