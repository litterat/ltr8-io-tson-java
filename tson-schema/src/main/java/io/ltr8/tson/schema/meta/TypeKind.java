package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code type_kind} enum (Part 2 §4.1, §8.1) -- every resolved {@link
 * TypeDefinition} carries exactly one, the REQUIRED, never-defaulted {@code kind} field.
 */
public enum TypeKind {
    ATOM, PRODUCT, SUM, REFERENCE
}
