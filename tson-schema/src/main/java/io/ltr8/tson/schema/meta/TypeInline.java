package io.ltr8.tson.schema.meta;

/**
 * A type written where it is used: the structural bodies, each of which is also a {@link Product}, a {@link Sum} or
 * an {@link Atom}. Spike for proposal/inline-types.md §7.1.
 */
public sealed interface TypeInline extends Type, TypeExpr
        permits ArrayBody, MapBody, TupleBody, ChoiceBody, ReferenceBody {
}
