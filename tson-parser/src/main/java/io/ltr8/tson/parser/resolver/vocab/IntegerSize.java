package io.ltr8.tson.parser.resolver.vocab;

import java.math.BigInteger;

/**
 * Fixed-width integer representation (meta-kernel's {@code integer_size}: {@code { bits: integer
 * signed: boolean } }). The representable range derives from width and signedness alone -- "Signed
 * n-bit is [-2^(n-1), 2^(n-1)-1], unsigned n-bit is [0, 2^n-1]" -- so {@code int32} and {@code
 * uint32} are the same constructor, {@link io.ltr8.tson.parser.resolver.vocab.IntegerType}, applied
 * to {@code new IntegerSize(32, true)} vs {@code new IntegerSize(32, false)}.
 */
public record IntegerSize(int bits, boolean signed) {

    public IntegerSize {
        if (bits <= 0) {
            throw new IllegalArgumentException("bits must be positive, was " + bits);
        }
    }

    public BigInteger minValue() {
        return signed ? BigInteger.TWO.pow(bits - 1).negate() : BigInteger.ZERO;
    }

    public BigInteger maxValue() {
        return signed ? BigInteger.TWO.pow(bits - 1).subtract(BigInteger.ONE) : BigInteger.TWO.pow(bits).subtract(BigInteger.ONE);
    }

    /**
     * The narrowest standard boxed integer type that holds every value this size admits --
     * {@code int8}/{@code int16}/{@code int32}/{@code int64} fit their same-named primitive
     * exactly, but an unsigned n-bit range needs the next-wider *signed* primitive (unsigned 8-bit
     * 0..255 overflows signed {@code byte}'s 127 max, so {@code uint8}'s natural host type is
     * {@code Short}, not {@code Byte}) since Java has no unsigned primitives. Widths beyond 64 bits,
     * signed or not, have no primitive that fits and fall through to {@link BigInteger}.
     */
    public Class<?> hostType() {
        BigInteger min = minValue();
        BigInteger max = maxValue();
        if (fits(min, max, Byte.MIN_VALUE, Byte.MAX_VALUE)) {
            return Byte.class;
        }
        if (fits(min, max, Short.MIN_VALUE, Short.MAX_VALUE)) {
            return Short.class;
        }
        if (fits(min, max, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            return Integer.class;
        }
        if (fits(min, max, Long.MIN_VALUE, Long.MAX_VALUE)) {
            return Long.class;
        }
        return BigInteger.class;
    }

    private static boolean fits(BigInteger min, BigInteger max, long primitiveMin, long primitiveMax) {
        return min.compareTo(BigInteger.valueOf(primitiveMin)) >= 0 && max.compareTo(BigInteger.valueOf(primitiveMax)) <= 0;
    }
}
