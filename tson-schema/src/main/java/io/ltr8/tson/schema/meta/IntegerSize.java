package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;

import java.math.BigInteger;

/**
 * The meta-kernel's {@code integer_size} record (Part 2 §8.1, §9): a fixed-width integer
 * representation, bit width paired with two's-complement signedness -- {@code integer_type.size}'s
 * field type. {@code bits} is the kernel's arbitrary-precision {@code integer}, hence {@link
 * BigInteger} rather than a Java primitive width, even though every built-in width in practice
 * (8..256) fits comfortably in an {@code int}. Pure data -- range/host-type behavior derived from a
 * width (minValue/maxValue/hostType) lives on {@code tson-parser}'s {@code IntegerParser}, which
 * consumes this, not here.
 *
 * <p>The canonical constructor is written out explicitly (compact, empty body) purely to carry
 * {@code @Record} -- required as soon as a second, convenience constructor exists (see {@link
 * IntegerType}'s own Javadoc for why {@code tson-bind} needs this).
 */
public record IntegerSize(BigInteger bits, boolean signed) {

    @Record
    public IntegerSize {
    }

    /** Convenience for the built-in width ladder (8..256), where an {@code int} literal reads better than {@code BigInteger.valueOf(...)} at every call site. */
    public IntegerSize(int bits, boolean signed) {
        this(BigInteger.valueOf(bits), signed);
    }
}
