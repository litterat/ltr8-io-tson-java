package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code type-ref = paren-type / bracket-type / map-type / sibling-ref / type-name "<" type-args ">" / type-name}
 * (Part 2 §12.1) -- a reference to a type at any position one is legal: a declaration's own body, field
 * types, type arguments, choice variants, elements, composition/refinement targets. A {@link SiblingRef} parses at
 * each of them and stands only at a record field's type (§5.2's position rule, stated by the desugar phase).
 *
 * <p><b>One tier.</b> A container admits a size specifier and an element {@code ?} wherever it stands, and a
 * declaration-level container arrives as {@code [type-params] type-ref} rather than through a production of its own.
 */
public sealed interface TypeRef permits ApplicationRef, ArrayRef, ChoiceRef, MapRef, NameRef, SiblingRef, TupleRef {
}
