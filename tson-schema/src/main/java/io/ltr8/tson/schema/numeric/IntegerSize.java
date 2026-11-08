package io.ltr8.tson.schema.numeric;

import io.ltr8.annotation.Record;

import java.math.BigInteger;

/**
 * The kernel's {@code numeric.integer_size} record (Part 2 §8.1, §9): a fixed-width integer
 * representation, bit width paired with two's-complement signedness -- {@code integer_type.size}'s
 * field type. {@code bits} is the kernel's arbitrary-precision {@code integer}, hence {@link
 * BigInteger} rather than a Java primitive width: it is not a {@code numeric.count}, which refines {@code
 * int64}, whose width is stated through this record, and its above-zero rule is {@link
 * IntegerType#coherenceCheck}'s.
 *
 * <p><b>The range a width admits is judged by bit length, never materialised</b> ({@link #admits},
 * {@link #within}): a signed {@code n}-bit width admits a value whose {@link BigInteger#bitLength} is below
 * {@code n}, an unsigned one a non-negative value whose bit length is at most {@code n}. That is exact and total
 * for any width a schema can state, where building {@code 2^n} is not: a {@code bits} of three billion is a
 * valid width with a range no host can hold.
 *
 * <p>The canonical constructor is written out explicitly (compact, empty body) purely to carry
 * {@code @Record} -- required as soon as a second, convenience constructor exists (see {@link
 * IntegerType}'s own Javadoc for why {@code ltr8-bind} needs this).
 */
public record IntegerSize(BigInteger bits, boolean signed) {

    @Record
    public IntegerSize {
    }

    /** Convenience for the built-in width ladder (8..256), where an {@code int} literal reads better than {@code BigInteger.valueOf(...)} at every call site. */
    public IntegerSize(int bits, boolean signed) {
        this(BigInteger.valueOf(bits), signed);
    }

    /**
     * Whether {@code value} is in this width's range. A width of no bits admits nothing; it is refused at load
     * ({@link IntegerType#coherenceCheck}). A width beyond {@link Integer#MAX_VALUE} bits admits every value of
     * its sign, since no {@link BigInteger} has that many bits.
     */
    public boolean admits(BigInteger value) {
        if (bits.signum() <= 0) {
            return false;
        }
        if (bits.bitLength() >= Integer.SIZE) {
            return signed || value.signum() >= 0;
        }
        int n = bits.intValue();
        return signed ? value.bitLength() < n : value.signum() >= 0 && value.bitLength() <= n;
    }

    /**
     * Whether every value this width admits, {@code wider} admits: a signed or an unsigned width within one of the
     * same signedness and at least as many bits, an unsigned one within a signed one of more bits, and a signed one
     * within no unsigned one, since it admits {@code -1}.
     */
    public boolean within(IntegerSize wider) {
        int order = bits.compareTo(wider.bits);
        if (signed == wider.signed) {
            return order <= 0;
        }
        return !signed && order < 0;
    }

    /** Whether {@code value} is below this width's floor: negative and out of range. */
    public boolean isBelow(BigInteger value) {
        return value.signum() < 0 && !admits(value);
    }

    /** Whether {@code value} is above this width's ceiling: non-negative and out of range. */
    public boolean isAbove(BigInteger value) {
        return value.signum() >= 0 && !admits(value);
    }

    /** The width as a message names it: {@code signed 32-bit}. */
    public String describe() {
        return (signed ? "signed " : "unsigned ") + bits + "-bit";
    }
}
