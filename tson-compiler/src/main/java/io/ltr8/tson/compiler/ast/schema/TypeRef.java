package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code type-name ["<" type-args ">"]} (Part 2 §12.1) -- a reference to a type: a {@link NameRef} or an {@link
 * ApplicationRef}. The shape mirrors the resolved model's {@code io.ltr8.tson.schema.meta.TypeRef}, the value of
 * {@code meta.type_ref}.
 */
public sealed interface TypeRef extends Reference permits NameRef, ApplicationRef {
}
