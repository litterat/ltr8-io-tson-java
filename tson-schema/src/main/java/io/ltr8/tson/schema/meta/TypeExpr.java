package io.ltr8.tson.schema.meta;

/**
 * What a type slot holds: a {@link TypeRef}, a name looked up in the closure, or a {@link TypeInline}, a type written
 * in place. Spike for proposal/inline-types.md §7.1; only {@link ArrayBody#elementType} is retyped.
 */
public sealed interface TypeExpr permits TypeRef, TypeInline {
}
