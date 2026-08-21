package io.ltr8.tson.compiler.ast.schema;

import java.util.Optional;

/**
 * {@code "[" element-type [ws ";" ws size-spec] ws "]"} (Part 2 §12.1, §5.3) -- an array type, legal at
 * <b>every</b> type-ref position: a declaration's own body, a field type, an element, a type argument, a
 * choice variant.
 *
 * <p>One production, not two. The grammar used to spell this twice -- a declaration-level form admitting a
 * size specifier and an element {@code ?}, and an inline form admitting neither -- with a prose tie-break
 * because {@code type-def} was otherwise ambiguous between them. The split existed because a sized form had
 * no inline representation to carry it; every form lifts to an entry now, so there is nothing left for the
 * restriction to protect, and it is gone rather than relocated.
 *
 * <p>{@code size} absent means unconstrained. Nesting is the recursion in {@link ElementType}, which holds
 * a plain {@link TypeRef}: {@code [[T; 2]; 3]} is this node twice over, needing no second node family.
 */
public record ArrayRef(ElementType elementType, Optional<SizeSpec> size) implements TypeRef {
}
