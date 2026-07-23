package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's structural root, {@code top => {}} (Part 2 §4.1) -- every type in the schema
 * IS-A this. A marker interface, not a class (Java records can't extend a class, only implement
 * interfaces), added purely to replicate the kernel's own composition chain as real Java
 * subtyping: {@code atom => top & {}}, {@code product => top & { ... }}, and {@code sum => top &
 * {}} become {@link Atom}/{@link Product}/{@link Sum} each {@code extends Top}, and {@code
 * reference => top & { target: type_name }} (which composes with {@code top} directly, not through
 * one of the three base kinds) becomes {@link Reference} implementing this interface directly.
 * Lets a consumer test kind ancestry with an ordinary {@code instanceof Product}/{@code instanceof
 * Atom} rather than switching on {@link TypeKind} by hand.
 *
 * <p>Deliberately separate from {@link TypeBody} (the sealed union {@code tson-bind}'s generic
 * writer already dispatches on for {@code !record}/{@code !array}/etc. type-refs) rather than
 * folded into it, even though {@code TypeBody}'s own Javadoc already describes it as "typed {@code
 * top} in the kernel" -- keeping the two hierarchies separate means this one can mirror the
 * kernel's own four names exactly (`Top`/`Atom`/`Product`/`Sum`) without disturbing `TypeBody`'s
 * existing role or its established `permits` list. Every {@code TypeBody} variant implements both.
 */
public sealed interface Top permits Atom, Product, Sum, Reference {
}
