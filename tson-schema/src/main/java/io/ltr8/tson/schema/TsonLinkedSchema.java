package io.ltr8.tson.schema;

import java.util.Map;
import java.util.Objects;

/**
 * The result of {@code TsonSchemaLinker.link} ({@code tson-compiler}) -- proof, at the type level, that a
 * {@link TsonSchema} has been through import merging, argument-bearing {@code type_ref} synthesis, and
 * reference validation, and is therefore self-contained (no dangling references) and safe to {@link
 * TsonSchemaRegistry#register}.
 *
 * <p><b>Wraps a {@link TsonSchema} rather than duplicating its fields</b> -- a {@code TsonLinkedSchema}
 * *is* a {@code TsonSchema} (the post-link {@code id}/{@code meta}/{@code imports}/{@code entries}/{@code
 * bootstrap}), plus the extra type-level fact "this one has been linked." Not a subtype ({@link
 * TsonSchema} is a record, and records are implicitly final, so extending it was never on the
 * table anyway) and not interchangeable with one -- a plain {@code TsonSchema} means "resolved, but
 * not necessarily linked"; a {@code TsonLinkedSchema} means "linked." Keeping them nominally
 * distinct means {@link TsonSchemaRegistry#register} can declare it only accepts a {@code
 * TsonLinkedSchema} and the compiler enforces "you must link before you register" -- no flag to
 * check, no way to forget.
 *
 * <p><b>{@link TsonSchemaRegistry} stores this type directly</b> -- every entry it ever holds arrived
 * via {@link TsonSchemaRegistry#register}, so every entry is, by construction, linked; there's no other
 * kind of thing to store. {@link TsonSchemaLoader#load} returns this type for the same reason.
 *
 * <p><b>{@code entryOrigins} is the one fact linking establishes that {@link TsonSchema} cannot hold.</b>
 * Merging an {@code !!import} flattens another schema's entries into this one's {@link TsonSchema#entries()},
 * which is what makes every reference resolvable in one namespace -- and also what erases which document each
 * entry was written in. A diagnostic against an imported entry needs that back: {@code /int32} at line 110 is
 * core.tn's, not the four-line schema a document named, and pairing the pointer with the importing schema's
 * identity would send a consumer to the wrong file. So the map is name -&gt; the canonical identity ([TSON-DATA]
 * §2.2.1) of the schema that declared it, populated as the merge happens and carried transitively (an entry
 * reached through two levels of import keeps its original author, not the intermediary).
 *
 * <p>It lives here rather than on {@link TsonSchema} because it is not part of the resolved schema value the
 * spec defines -- §9's {@code type_definition} has no such field, and {@code schema.meta} is a bind target
 * with a hand-written {@code equals} and the {@code @Record} constructor-selection trap. It lives here rather
 * than on the compiled schema because linking is the only phase that still knows it.
 */
public record TsonLinkedSchema(TsonSchema schema, Map<String, String> entryOrigins) {

    public TsonLinkedSchema {
        Objects.requireNonNull(schema, "schema");
        entryOrigins = Map.copyOf(entryOrigins);
    }

    /**
     * A schema every one of whose entries is its own -- what {@code TsonSchemaLinker} produces for a document
     * with no {@code !!import}, and the shape a caller assembling a {@link TsonSchema} by hand wants.
     */
    public TsonLinkedSchema(TsonSchema schema) {
        this(schema, Map.of());
    }

    /**
     * The canonical identity of the schema that declared {@code entryName} -- this schema's own {@code !!id}
     * for anything it declares itself or was never told about, since an entry with no recorded origin was
     * never merged in from anywhere else.
     */
    public String originOf(String entryName) {
        return entryOrigins.getOrDefault(entryName, schema.id());
    }
}
