package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code element_state} enum (Part 2 §5.3, §8.1), shared by array elements and tuple
 * positions (§5.3: "tuples and arrays share the two-member element_state enumeration"). A record field
 * states its presence through {@link RecordField}'s own facts instead. {@link #REQUIRED} is the default,
 * omitted from output.
 */
public enum ElementState {
    REQUIRED, OPTIONAL
}
