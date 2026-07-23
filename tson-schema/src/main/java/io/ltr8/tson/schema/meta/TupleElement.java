package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;

/**
 * The meta-kernel's {@code tuple_element} record (Part 2 §5.3, §8.1): one position of a resolved
 * {@link TupleBody}. {@code state} shares the two-member {@code element_state} enumeration with
 * array elements, not {@link FieldState}'s five members; bound through plain generic binding it
 * always appears in output, even at its nominal {@link ElementState#REQUIRED} default.
 */
public record TupleElement(@Field("element_type") TypeRef elementType, ElementState state) {

    /** A plain {@code REQUIRED} position. */
    public static TupleElement required(TypeRef elementType) {
        return new TupleElement(elementType, ElementState.REQUIRED);
    }
}
