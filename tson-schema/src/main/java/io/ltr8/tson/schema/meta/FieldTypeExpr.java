package io.ltr8.tson.schema.meta;

/**
 * What a record field's {@code type} holds, the kernel's {@code meta.field_type_expr}: a {@link Reference} (a type's
 * name, a parameter, or a sibling reference, {@code kind!}), or a {@link TypeInline}, a type written in place. {@link
 * RecordField#type} is typed by it, and no other slot is.
 */
public sealed interface FieldTypeExpr permits Reference, TypeInline {

    /** The canonical text of a reference, or the written form of an inline type ({@link TypeInline#text}). */
    String text();
}
