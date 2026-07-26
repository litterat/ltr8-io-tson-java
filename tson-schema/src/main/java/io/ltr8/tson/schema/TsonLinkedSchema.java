package io.ltr8.tson.schema;

import java.util.Objects;

/**
 * The result of {@link SchemaLinker#link} -- proof, at the type level, that a {@link TsonSchema}
 * has been through import merging, argument-bearing {@code type_ref} synthesis, and reference
 * validation, and is therefore self-contained (no dangling references) and safe to {@link
 * TsonSchemaRegistry#register}.
 *
 * <p><b>Wraps a {@link TsonSchema} rather than duplicating its fields</b> (2026-07-27, on the
 * user's own explicit direction, correcting an earlier version of this class that had the same
 * five components flattened directly onto it) -- a {@code TsonLinkedSchema} *is* a {@code
 * TsonSchema} (the post-link {@code id}/{@code meta}/{@code imports}/{@code entries}/{@code
 * bootstrap}), plus the extra type-level fact "this one has been linked." Not a subtype ({@link
 * TsonSchema} is a record, and records are implicitly final, so extending it was never on the
 * table anyway) and not interchangeable with one -- a plain {@code TsonSchema} means "resolved, but
 * not necessarily linked"; a {@code TsonLinkedSchema} means "linked." Keeping them nominally
 * distinct means {@link TsonSchemaRegistry#register} can declare it only accepts a {@code
 * TsonLinkedSchema} and the compiler enforces "you must link before you register" -- no flag to
 * check, no way to forget.
 *
 * <p><b>{@link TsonSchemaRegistry} stores this type directly</b> -- every entry it ever holds arrived
 * via {@link #register}, so every entry is, by construction, linked; there's no other kind of
 * thing to store. {@link TsonSchemaLoader#load} returns this type for the same reason.
 */
public record TsonLinkedSchema(TsonSchema schema) {

    public TsonLinkedSchema {
        Objects.requireNonNull(schema, "schema");
    }
}
