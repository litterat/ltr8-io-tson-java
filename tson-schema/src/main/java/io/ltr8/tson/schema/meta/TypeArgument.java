package io.ltr8.tson.schema.meta;

import io.ltr8.tson.parser.ast.TokenValue;

/**
 * The meta-kernel's {@code type_argument} record (Part 2 §8.1, §9): one positional argument of a
 * resolved {@link TypeRef} -- {@code { (name: type_ref | value: value) }}, a REQUIRED field
 * *group* (§5.11) in the kernel's own terms: exactly one of {@code name}/{@code value} is present,
 * never both, never neither.
 *
 * <p><b>Modeled as a sealed interface ({@code Ref}/{@code Value}), not a plain record with two
 * {@code Optional} fields, even though the latter is the more literal translation of the kernel's
 * own shape.</b> {@link TypeRef} and {@code TypeArgument} are mutually recursive ({@code
 * TypeRef.arguments: List<TypeArgument>}, and a reference argument wraps a {@code TypeRef} right
 * back) -- e.g. {@code array_ranged<array_ranged<T, N, N>, N, N>}, a real desugared shape (§5.3's
 * {@code grid => <T, N> [[T; N]; N]}). {@code tson-bind}'s record resolution ({@code
 * DefaultRecordBinder}) eagerly resolves every field's descriptor as part of building the record's
 * own, with no cycle detection; tried as a plain record here, that recurses forever the moment
 * anything actually has a non-empty {@code arguments} list (confirmed empirically: every test in
 * this module started failing with {@code StackOverflowError} the moment {@code array_min}
 * resolution -- the first real user of non-empty arguments -- exercised it). {@code
 * DefaultUnionBinder} exists to defer exactly this: its own Javadoc/comment states it deliberately
 * does not resolve member descriptors up front, "by using the actual member classes the resolution
 * loop is broken." A sealed interface is therefore not a stylistic choice here -- it is the one
 * shape that lets {@code tson-bind} bind a mutually-recursive pair like this at all today.
 *
 * <p>The cost: since {@code Ref}/{@code Value} are {@code DataClassUnion} members with no {@code
 * @Typename}, {@code TsonMapper.toTson} writes them with a spurious {@code !ref}/{@code !value}
 * type-ref the kernel's own resolved form doesn't have (a real, {@code toTson}-surfaced divergence,
 * not silently swept aside) -- same value, same field-group semantics, just an extra tag; no
 * {@code @Typename} choice removes it, since the divergence is the tag's *presence*, not its name.
 */
public sealed interface TypeArgument {

    record Ref(TypeRef ref) implements TypeArgument {
    }

    record Value(TokenValue value) implements TypeArgument {
    }
}
