package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;

/**
 * The meta-kernel's {@code type_argument} record (Part 2 §8.1, §9): one positional argument of a
 * resolved {@link TypeRef} -- {@code { (name: type_ref | value: value) }}, a REQUIRED field
 * *group* (§5.11) in the kernel's own terms: exactly one of {@code name}/{@code value} is present,
 * never both, never neither.
 *
 * <p><b>Modeled as a sealed interface ({@code Ref}/{@code Value}), not a plain record with two
 * {@code Optional} fields.</b> It is the labelled choice the kernel declares, and modelling a choice as a
 * choice is the whole of the reason. A plain record with two {@code Optional} fields would be the more
 * literal translation of the wire shape and a worse model of it: nothing in the type would say that exactly
 * one is present.
 *
 * <p>It also used to be the only shape that <em>worked</em>. {@link TypeRef} and {@code TypeArgument} are
 * mutually recursive ({@code TypeRef.arguments: List<TypeArgument>}, and a reference argument wraps a
 * {@code TypeRef} right back) -- {@code box<box<text>>}, an ordinary nested application -- and
 * {@code tson-bind} resolved every component's descriptor eagerly with no cycle detection, so a plain record
 * here recursed until the stack went. {@code DataBindContext} carries a cycle guard now, so that constraint
 * is gone and this shape stands on its own merits.
 *
 * <p>The cost: since {@code Ref}/{@code Value} are {@code DataClassUnion} members with no {@code
 * @Typename}, {@code TsonObjectWriter.toTson} writes them with a spurious {@code !ref}/{@code !value}
 * type-ref the kernel's own resolved form doesn't have (a real, {@code toTson}-surfaced divergence,
 * not silently swept aside) -- same value, same field-group semantics, just an extra tag; no
 * {@code @Typename} choice removes it, since the divergence is the tag's *presence*, not its name.
 *
 * <p><b>{@code @Field("name")} on {@code Ref} is what makes the shape readable again.</b> A labelled choice
 * has no tag to dispatch on, so its member is chosen by which field arrived, and the member a field selects
 * is found by that member's own single component wire-name -- which is therefore the field's name, not the
 * component's incidental Java one ({@code GroupUnionBindReader}). It also brings {@code toTson}'s output
 * closer to the kernel's own spelling, which writes {@code name} here.
 */
public sealed interface TypeArgument {

    record Ref(@Field("name") TypeRef ref) implements TypeArgument {
    }

    record Value(Token value) implements TypeArgument {
    }
}
