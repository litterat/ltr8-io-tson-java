package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code field_state} enum (Part 2 §5.2, §8.1) -- {@link #REQUIRED} is the
 * default, omitted from resolver output text ("Fields at their default values are omitted",
 * `meta-kernel-resolved.tn1`'s own conventions note). Five members, used only by {@code
 * RecordField}; {@link ElementState}'s two members are the array/tuple-position counterpart
 * (§5.3: "tuples and arrays share the two-member element_state enumeration").
 */
public enum FieldState {
    REQUIRED, REQUIRED_DEFAULT, REQUIRED_FIXED, OPTIONAL, OPTIONAL_FIXED
}
