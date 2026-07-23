package io.ltr8.tson.parser.ast.schema;

/**
 * {@code type-ref = paren-type / inline-array / type-name "<" type-args ">" / type-name} (Part 2
 * §12.1) -- a reference to a type at any type-ref position: field types, type arguments, choice
 * variants, composition/refinement targets. Legal at both inline and declaration-level positions
 * (§5.3); the declaration-level-only sugar (size specifiers, element/position {@code ?}) lives in
 * {@link ContainerDef} instead, which is not a {@code TypeRef} variant.
 */
public sealed interface TypeRef permits ChoiceRef, InlineArrayRef, InlineTupleRef, GenericRef, SimpleRef {
}
