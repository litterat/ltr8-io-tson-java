package io.ltr8.tson.schema.meta;

/**
 * The kind of a resolved {@link TypeDefinition} (Part 2 §4.1, §8.1) -- derived from the entry's own supertypes and
 * body rather than declared, since the meta-kernel has no {@code type_kind}. {@code TypeDefinition.kind} carries it as
 * an {@code @Unbound} component: computed at resolution for this resolver's own use, never written.
 */
public enum TypeKind {
    ATOM, PRODUCT, SUM, REFERENCE,

    /**
     * An entry that describes something other than a data value -- meta-schema vocabulary riding along in a
     * schema map. Its body is a {@link Data}; nothing may be typed by it.
     */
    DATA,

    /**
     * An entry that declares type parameters -- [TSON-SCHEMA] §5.10's <b>open</b> entry, whose body is a
     * {@link TemplateBody}. A template is not a type: it cannot validate data, and naming one where a type is
     * expected is a resolver error. Like {@link #REFERENCE} it is a {@code type_kind} and not a base kind
     * (§4.1), and it says nothing about what an application of the template will produce -- that is the kind
     * of the entry materialisation mints, derived from the constructor its closed body applies.
     */
    TEMPLATE
}
