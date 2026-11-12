package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code type-ref = paren-type / bracket-type / brace-type / type-name "<" type-args ">" / type-name} (Part 2 §12.1) --
 * a type expression, at any position one is legal: a declaration's own body, field types, type arguments, choice
 * variants, elements, composition/refinement targets. It is a {@link TypeRef} or one of the structural forms, {@link
 * ChoiceExpr}, {@link ArrayExpr}, {@link TupleExpr}, {@link MapExpr} and {@link RecordExpr}, each of which holds
 * further expressions.
 *
 * <p><b>One tier.</b> A container admits a size specifier and an element {@code ?} wherever it stands, and a
 * declaration-level container arrives as {@code [type-params] type-ref} rather than through a production of its own.
 */
public sealed interface TypeExpr extends FieldTypeExpr
        permits TypeRef, ArrayExpr, ChoiceExpr, MapExpr, TupleExpr, RecordExpr, InlineConstruction {
}
