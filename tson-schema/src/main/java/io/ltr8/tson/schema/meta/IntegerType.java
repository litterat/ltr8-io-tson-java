package io.ltr8.tson.schema.meta;

import java.math.BigInteger;
import java.util.Optional;

/**
 * The meta-kernel's {@code integer_type} constructor (Part 2 §5.6/§9): the integer family's atom
 * constraint vocabulary -- bit width/signedness (via {@link IntegerSize}), bounds, and a
 * multiple-of constraint. Pure constraint values, no parsing or validation behavior -- {@code
 * tson-parser}'s {@code IntegerParser} holds one of these and does the actual reading/writing.
 */
public record IntegerType(
        Optional<IntegerSize> size,
        Optional<BigInteger> min,
        Optional<BigInteger> exclusiveMin,
        Optional<BigInteger> max,
        Optional<BigInteger> exclusiveMax,
        Optional<BigInteger> multipleOf) {

    /** The kernel's unconstrained, arbitrary-precision {@code integer}. */
    public static final IntegerType UNCONSTRAINED = new IntegerType(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

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
