package io.ltr8.tson.compiler.ast.schema;

import java.util.Optional;

/**
 * {@code "[" element-type [ws ";" ws size-spec] ws "]"} (Part 2 §12.1, §5.3) -- an array type, legal at
 * <b>every</b> type expression position: a declaration's own body, a field type, an element, a type argument, a
 * choice variant.
 *
 * <p>One production serves every position: a size specifier and an element {@code ?} are admitted wherever the
 * array stands, the construction it desugars to carrying them.
 *
 * <p>{@code size} absent means unconstrained. Nesting is the recursion in {@link ElementType}, which holds
 * a plain {@link TypeExpr}: {@code [[T; 2]; 3]} is this node twice over, needing no second node family.
 */
public record ArrayExpr(ElementType elementType, Optional<SizeSpec> size) implements TypeExpr {
}
