package io.ltr8.tson.compiler.ast.schema;

/**
 * What a record field's or a field group member's type holds as written (Part 2 §12.1): a {@link TypeExpr}, or the
 * {@link DependentType} the grammar admits there and nowhere else, so no other slot can hold one.
 */
public sealed interface FieldTypeExpr permits TypeExpr, DependentType {
}
