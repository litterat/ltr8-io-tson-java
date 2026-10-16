package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code type => abstract top & chained & {}} (Part 2 §4.1, SPEC-FEEDBACK.md #9, #14, #15): the
 * kind of the kinds that describe the shape of a data value. {@link Atom}, {@link Product} and {@link Sum} each
 * {@code extends Type}, as {@code atom}, {@code product} and {@code sum} compose with {@code type}; {@link Data}
 * and {@link Reference} do not, as their kernel entries compose with {@code top} directly. So a body describes a
 * type exactly when it is an {@code instanceof Type}, and every type body carries its chain ({@link Chained}).
 *
 * <p>Every body record carries {@code chain} as its first component, mirroring the constructor's flattened
 * shape, where {@code chained}'s field comes first.
 *
 * <p>Not a base kind: {@link TypeKind} has no {@code TYPE}, and a kind is read from the base kind below it.
 */
public sealed interface Type extends Top, Chained permits Atom, Product, Sum {

    @Override
    Type withChain(TypeChain chain);

    @Override
    default Type withSupertypes(List<String> supertypes) {
        return withChain(chain().withSupertypes(supertypes));
    }

    @Override
    default Type withSubtypes(List<String> subtypes) {
        return withChain(chain().withSubtypes(subtypes));
    }
}
