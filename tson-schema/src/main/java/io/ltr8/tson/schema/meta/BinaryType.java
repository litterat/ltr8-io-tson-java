package io.ltr8.tson.schema.meta;

import java.util.Optional;

/**
 * meta.tn1's {@code binary} constructor (§5.3's four binary atoms, RFC 4648) -- one class, not one
 * per encoding: {@code binary}'s only field beyond the RFC pin is {@code encoding: binary_encoding},
 * a closed four-value enum, exactly the same shape as {@link IntegerType}'s {@code size} or {@link
 * FloatType}'s {@code format} -- a single constructor parameterized by one of its own fields, not
 * four different constructors. Pure constraint values, no parsing/validation behavior -- {@code
 * tson-parser}'s {@code BinaryParser} holds one of these and does the actual reading/writing.
 *
 * <p>Named {@code BinaryType} here despite meta.tn1's constructor being spelled {@code binary}, not
 * {@code binary_type} like every other constructor -- see {@code SPEC-FEEDBACK.md} #11.
 *
 * <p>{@code minLength}/{@code maxLength} are modeled for structural fidelity (meta.tn1 defines
 * them on the constructor) but unexercised by any built-in instance, the same as {@link
 * FloatType}'s bounds -- {@code base64 => !binary BASE64} and its three siblings in core.tn1 are all
 * unconstrained beyond {@code encoding}.
 */
public record BinaryType(Encoding encoding, Optional<Integer> minLength, Optional<Integer> maxLength) {

    public enum Encoding {
        BASE64("base64"), BASE64URL("base64url"), BASE32("base32"), HEX("hex");

        private final String typeName;

        Encoding(String typeName) {
            this.typeName = typeName;
        }

        /** §5.3's built-in annotation name for this encoding, e.g. {@code !base64}. */
        public String typeName() {
            return typeName;
        }
    }

    /** {@code base64 => !binary BASE64}, and so on for the other three -- §5.3's four built-in annotations, all unconstrained beyond {@code encoding}. */
    public static final BinaryType BASE64 = new BinaryType(Encoding.BASE64, Optional.empty(), Optional.empty());
    public static final BinaryType BASE64URL = new BinaryType(Encoding.BASE64URL, Optional.empty(), Optional.empty());
    public static final BinaryType BASE32 = new BinaryType(Encoding.BASE32, Optional.empty(), Optional.empty());
    public static final BinaryType HEX = new BinaryType(Encoding.HEX, Optional.empty(), Optional.empty());

    /** §5.3's built-in annotation name for this instance's {@link #encoding}, e.g. {@code !base64}. */
    public String typeName() {
        return encoding.typeName();
    }
}
