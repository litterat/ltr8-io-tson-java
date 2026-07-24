package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's structural root, {@code top => {}} (Part 2 §4.1) -- every type in the schema
 * IS-A this, and it is {@link TypeDefinition#body}'s own declared type. A marker interface, not a
 * class (Java records can't extend a class, only implement interfaces), added purely to replicate
 * the kernel's own composition chain as real Java subtyping: {@code atom => top & {}}, {@code
 * product => top & { ... }}, and {@code sum => top & {}} become {@link Atom}/{@link Product}/
 * {@link Sum} each {@code extends Top}, and {@code reference => top & { target: type_name } }
 * (which composes with {@code top} directly, not through one of the three base kinds) becomes
 * {@link Reference} implementing this interface directly. Lets a consumer test kind ancestry with
 * an ordinary {@code instanceof Product}/{@code instanceof Atom} rather than switching on {@link
 * TypeKind} by hand, and also lets {@code tson-bind}'s generic writer/reader dispatch on this same
 * sealed hierarchy directly for {@code !record}/{@code !array}/etc. type-refs -- {@code
 * DefaultUnionBinder} recurses through a multi-level sealed hierarchy like this one (a permitted
 * subclass that's itself sealed is flattened, not left as an unusable "member"), which is what
 * makes binding straight against {@code Top}/{@link Atom} practical at all (see {@code tson-bind}'s
 * own README "Under development" history for the bug this fixed).
 *
 * <p>Until 2026-07-24 this lived alongside a separate, single-level sealed union ({@code
 * TypeBody}) that {@code TypeDefinition.body}/{@code tson-bind}'s writer actually used, kept apart
 * only because binding directly against a multi-level sealed hierarchy like this one didn't work
 * yet. Once the {@code DefaultUnionBinder} fix landed, that separation no longer bought anything,
 * so {@code TypeBody} was deleted and {@code TypeDefinition.body} retyped to {@code Top} directly --
 * one hierarchy, matching the kernel's own naming exactly, not two.
 */
public sealed interface Top permits Atom, Product, Sum, Reference {
}
