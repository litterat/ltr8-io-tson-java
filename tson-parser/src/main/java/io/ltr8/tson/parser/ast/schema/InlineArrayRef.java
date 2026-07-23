package io.ltr8.tson.parser.ast.schema;

/**
 * {@code "[" type-ref ws "]"} (Part 2 §12.1, §5.3) -- the plain-array inline sugar, legal at any
 * type-ref position. No size specifier and no element {@code ?}: those are declaration-level-only
 * syntax, modeled by {@link ArrayContainerDef} instead (§5.3's inline/declaration-level split).
 * Desugars to {@code !array { element_type: T }} at resolution.
 */
public record InlineArrayRef(TypeRef elementType) implements TypeRef {
}
