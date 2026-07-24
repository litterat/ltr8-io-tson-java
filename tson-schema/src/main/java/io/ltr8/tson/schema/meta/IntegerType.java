package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;
import java.util.Optional;

/**
 * The meta-kernel's {@code integer_type} constructor (Part 2 §5.6/§9): the integer family's atom
 * constraint vocabulary -- bit width/signedness (via {@link IntegerSize}), bounds, and a
 * multiple-of constraint. Pure constraint values, no parsing or validation behavior -- {@code
 * tson-parser}'s {@code IntegerParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant (added 2026-07-23, alongside {@code MetaKernelParser}): {@code
 * integer => !integer_type {}} is a constructor-application instance (§5.5) whose resolved body is
 * exactly this shape, bound via plain {@code TsonMapper.toObject} the same way every other {@link
 * Top} variant round-trips through generic binding -- it's the first of the atom
 * constraint-vocabulary families to be modeled this way, since its fields (unlike {@code
 * text_type}/{@code uri_type}/{@code regex_type}'s) already needed no field-group-in-a-bound-
 * instance design work -- mutual exclusion between {@code min}/{@code exclusiveMin} and between
 * {@code max}/{@code exclusiveMax} is already enforced by this record's own compact constructor,
 * not a separate wrapper.
 *
 * <p>The canonical (compact) constructor carries an explicit {@code @Record} -- required as soon
 * as a record has more than one public constructor (the convenience {@link
 * #IntegerType(IntegerSize)} one below is the second): {@code tson-bind}'s {@code
 * DefaultRecordBinder.getConstructor} only auto-picks a bare class's sole constructor when exactly
 * one exists, and throws {@code CodeAnalysisException} ("Could not find constructor") otherwise
 * unless one is explicitly marked. Confirmed empirically -- {@code MetaKernelParser} binding {@code
 * integer => !integer_type {}} via plain {@code TsonMapper.toObject} was the first real use of
 * this class as a bind *target* (every earlier use just constructed it directly in Java), and
 * surfaced this immediately.
 */
@Typename(name = "integer_type")
public record IntegerType(
        Optional<IntegerSize> size,
        Optional<BigInteger> min,
        @Field("exclusive_min") Optional<BigInteger> exclusiveMin,
        Optional<BigInteger> max,
        @Field("exclusive_max") Optional<BigInteger> exclusiveMax,
        @Field("multiple_of") Optional<BigInteger> multipleOf) implements Atom {

    /** The kernel's unconstrained, arbitrary-precision {@code integer}. */
    public static final IntegerType UNCONSTRAINED = new IntegerType(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    @Record
    public IntegerType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    /** {@code int32 => !integer ^ { size: { bits: 32 signed: true } } } -- e.g. {@code new IntegerType(new IntegerSize(32, true))}. */
    public IntegerType(IntegerSize size) {
        this(Optional.of(size), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** {@code positive_integer => !integer ^ { min: 1 } }. */
    public static IntegerType ofMin(BigInteger min) {
        return new IntegerType(Optional.empty(), Optional.of(min), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** {@code negative_integer => !integer ^ { max: -1 } }. */
    public static IntegerType ofMax(BigInteger max) {
        return new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(max), Optional.empty(), Optional.empty());
    }
}
