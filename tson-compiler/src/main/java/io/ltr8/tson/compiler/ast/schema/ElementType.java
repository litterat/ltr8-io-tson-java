package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code element-type = type-ref ["?"]} (Part 2 §12.1, §5.3) -- one position inside an {@link ArrayRef},
 * a {@link TupleRef}, or a {@link MapRef}'s value.
 *
 * <p>The optional {@code ?} here is element/tuple-position optionality (a container-level fact), distinct
 * from a field's own {@code ?} (§5.2) even though both reuse the same token. They cannot collide: a field
 * is {@code field-name ":" type-ref ["?"]}, so in {@code xs: [T?]?} the inner {@code ?} is the element's
 * and the outer the field's.
 *
 * <p>It holds a plain {@link TypeRef} and nothing else. Nesting needs no case of its own, because a bracket
 * or map form <em>is</em> a type-ref -- which is the whole point of collapsing the two container tiers.
 */
public record ElementType(TypeRef typeRef, boolean optional) {
}
