package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code type-ref = paren-type / bracket-type / map-type / type-name "<" type-args ">" / type-name}
 * (Part 2 §12.1) -- a reference to a type at any position one is legal: a declaration's own body, field
 * types, type arguments, choice variants, elements, composition/refinement targets.
 *
 * <p><b>One tier, not two.</b> The grammar used to split every container into a declaration-level form
 * admitting a size specifier and an element {@code ?} and an inline form admitting neither. Both are this
 * now, everywhere, and a declaration-level container arrives as {@code [type-params] type-ref} rather than
 * through a production of its own.
 */
public sealed interface TypeRef permits ArrayRef, ChoiceRef, GenericRef, MapRef, SimpleRef, TupleRef {
}
