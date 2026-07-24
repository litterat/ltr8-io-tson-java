package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

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
 * {@code binary_type} like every other constructor -- see {@code SPEC-FEEDBACK.md} #11. The
 * {@code @Typename} below is {@code "binary"} to match, not {@code "binary_type"}.
 *
 * <p>{@code minLength}/{@code maxLength} are modeled for structural fidelity (meta.tn1 defines
 * them on the constructor) but unexercised by any built-in instance, the same as {@link
 * FloatType}'s bounds -- {@code base64 => !binary BASE64} and its three siblings in core.tn1 are all
 * unconstrained beyond {@code encoding}.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24): {@code base64 => !binary BASE64} and its
 * three siblings are constructor-application instances (§5.5) whose resolved bodies are exactly
 * {@link #BASE64}/{@link #BASE64URL}/{@link #BASE32}/{@link #HEX} -- each a positional-form
 * instance (§5.6: a bare token filling {@code binary}'s sole {@code REQUIRED} field, {@code
 * encoding}), not a braced body.
 */
@Typename(name = "binary")
public record BinaryType(Encoding encoding, @Field("min_length") Optional<Integer> minLength,
                          @Field("max_length") Optional<Integer> maxLength) implements Atom {

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
