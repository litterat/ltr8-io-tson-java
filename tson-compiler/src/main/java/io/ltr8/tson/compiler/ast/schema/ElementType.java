package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code element-type = type-ref ["?"]} (Part 2 §12.1, §5.3) -- one position inside an {@link ArrayRef},
 * a {@link TupleRef}, or a {@link MapRef}'s value.
 *
 * <p>The {@code ?} here makes the position {@code voidable}: the void sentinel {@code _} may stand in it. It is
 * the same fact a field's type {@code ?} states ({@link FieldDef.FieldType#voidable}), at a container position.
 * In {@code xs: [T?]?} the inner {@code ?} is the element's and the outer the field's type's; a container's
 * position is never missing, so there is no counterpart of the field name's {@code ?}.
 *
 * <p>It holds a plain {@link TypeRef} and nothing else. Nesting needs no case of its own, because a bracket
 * or map form <em>is</em> a type-ref -- which is the whole point of collapsing the two container tiers.
 */
public record ElementType(TypeRef typeRef, boolean voidable) {
}
