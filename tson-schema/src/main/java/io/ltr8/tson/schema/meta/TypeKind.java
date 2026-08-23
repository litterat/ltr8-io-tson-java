package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code type_kind} enum (Part 2 §4.1, §8.1) -- every resolved {@link
 * TypeDefinition} carries exactly one, the REQUIRED, never-defaulted {@code kind} field.
 */
public enum TypeKind {
    ATOM, PRODUCT, SUM, REFERENCE,

    /**
     * An entry that describes something other than a data value -- meta-schema vocabulary riding along in a
     * schema map. Its body is a {@link Data}; nothing may be typed by it.
     */
    DATA
}
