package io.ltr8.tson.schema.meta;

import java.util.Optional;

/**
 * What a type slot holds, the kernel's {@code meta.type_expr}: a {@link TypeRef}, a name looked up in the closure, or a
 * {@link TypeInline}, a type written where it is used. It types {@link ArrayBody#elementType}, {@link
 * MapBody#valueType}, {@link NamespaceBody#valueType}, {@link TupleElement#elementType} and each of {@link
 * ChoiceBody#variants}; a record field's slot is the wider {@link FieldTypeExpr}.
 *
 * <p>A consumer switches on the two: a name is looked up, and an inline type is the type. A name is string-class and
 * the wrapper brace-class, so the two are told apart by form when read ([TSON-SCHEMA] §5.4).
 */
public sealed interface TypeExpr permits TypeRef, TypeInline {

    /** The canonical text of a name, or the written form of an inline type ({@link TypeInline#text}). */
    String text();

    /** {@code expr} as the type it names, or empty where it is a {@link TypeInline}. */
    static Optional<TypeRef> named(TypeExpr expr) {
        return expr instanceof TypeRef reference ? Optional.of(reference) : Optional.empty();
    }
}
