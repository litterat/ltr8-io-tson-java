package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code type => top & {}} (Part 2 §4.1, SPEC-FEEDBACK.md #9): the kind of the kinds that describe
 * the shape of a data value. {@link Atom}, {@link Product} and {@link Sum} each {@code extends Type}, as {@code atom},
 * {@code product} and {@code sum} compose with {@code type}; {@link Data}, {@link Reference} and {@link TemplateBody}
 * do not, as their kernel entries compose with {@code top} directly. So a body describes a type exactly when it is
 * an {@code instanceof Type}.
 *
 * <p>Not a base kind: {@link TypeKind} has no {@code TYPE}, and a kind is read from the base kind below it.
 */
public sealed interface Type extends Top permits Atom, Product, Sum {
}
