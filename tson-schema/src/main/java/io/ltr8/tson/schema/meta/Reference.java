package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;

/**
 * What the kernel's {@code reference} atom reads to, as written: a name, an application, a parameter or a sibling
 * reference. A {@link TypeRef} (a {@link NameRef}, an {@link ApplicationRef}, or inside an open record a {@link
 * ParamRef}) names a type; a {@link SiblingRef}, {@code kind!}, names a field of the enclosing record whose value
 * chooses the type.
 *
 * <p><b>A sibling reference stands only at a record field's type</b> ([TSON-SCHEMA] §5.2): {@link RecordField#type}
 * is typed {@code Reference}, and every other slot holding a reference -- an alias target, an element, key or value
 * type, a choice variant, a tuple element, a parent, an argument -- is typed {@link TypeRef}, so the position rule is
 * the Java type. A parameter stands at a record field's type and as an argument, and is a {@link TypeRef} for the
 * second.
 *
 * <p><b>Identity</b> is the variant and its components, {@code annotations} excluded: {@code kind}, {@code kind<T>}
 * and {@code kind!} are three references.
 */
public sealed interface Reference permits TypeRef, SiblingRef {

    /** The annotations written on the reference itself; no part of its identity. */
    Annotations annotations();

    /** The canonical text, what resolved output writes unquoted: {@code kind}, {@code kind<T>} or {@code kind!}. */
    String text();

    /** A copy of this reference with {@code annotations} replaced -- every other component unchanged. */
    Reference withAnnotations(Annotations annotations);
}
