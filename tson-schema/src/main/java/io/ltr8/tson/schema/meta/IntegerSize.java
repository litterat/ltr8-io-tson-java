package io.ltr8.tson.schema.meta;

import java.math.BigInteger;

/**
 * The meta-kernel's {@code integer_size} record (Part 2 §8.1, §9): a fixed-width integer
 * representation, bit width paired with two's-complement signedness -- {@code integer_type.size}'s
 * field type. {@code bits} is the kernel's arbitrary-precision {@code integer}, hence {@link
 * BigInteger} rather than a Java primitive width, even though every built-in width in practice
 * (8..256) fits comfortably in an {@code int}.
 */
public record IntegerSize(BigInteger bits, boolean signed) {
}
