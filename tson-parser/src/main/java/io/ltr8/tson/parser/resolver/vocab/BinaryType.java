package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * meta.tn1's {@code binary} constructor (§5.3's four binary atoms, RFC 4648) -- one class, not one
 * per encoding: {@code binary}'s only field beyond the RFC pin is {@code encoding: binary_encoding},
 * a closed four-value enum, exactly the same shape as {@link IntegerType}'s {@code size} or {@link
 * FloatType}'s {@code format} -- a single constructor parameterized by one of its own fields, not
 * four different constructors. (An earlier version of this file *did* split it into four sibling
 * classes, one per encoding, since each encoding's decode algorithm is genuinely different --
 * but that's the same shape of branching {@code IntegerType} already does on {@code size.signed()}
 * and {@code FloatType} already does on {@code format}, not a reason to fork the class.)
 *
 * <p>Named {@code BinaryType} here despite meta.tn1's constructor being spelled {@code binary}, not
 * {@code binary_type} like every other constructor -- see {@code SPEC-FEEDBACK.md} #11.
 *
 * <p>{@code min_length}/{@code max_length} are modeled for structural fidelity (meta.tn1 defines
 * them on the constructor) but unexercised by any built-in instance, the same as {@link
 * FloatType}'s bounds -- {@code base64 => !binary BASE64} and its three siblings in core.tn1 are all
 * unconstrained beyond {@code encoding}.
 */
public record BinaryType(Encoding encoding, Optional<Integer> minLength, Optional<Integer> maxLength)
        implements AtomType<byte[]> {

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

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    /** §5.3's built-in annotation name for this instance's {@link #encoding}, e.g. {@code !base64}. */
    public String typeName() {
        return encoding.typeName();
    }

    @Override
    public byte[] read(TokenValue token) {
        String text = token.text();
        byte[] value = switch (encoding) {
            case BASE64 -> Base64Decoding.decode(text, Base64.getDecoder(), encoding.typeName());
            case BASE64URL -> Base64Decoding.decode(text, Base64.getUrlDecoder(), encoding.typeName());
            case BASE32 -> Base32Decoding.decode(text);
            case HEX -> decodeHex(text);
        };
        validate(value, text);
        return value;
    }

    private static byte[] decodeHex(String text) {
        try {
            return HEX_FORMAT.parseHex(text);
        } catch (IllegalArgumentException e) {
            throw new AtomParseException("'" + text + "' is not valid hex (RFC 4648 §8, §5.3): " + e.getMessage());
        }
    }

    /**
     * Encodes with padding, always -- the inverse of {@link #read}'s own padding requirement (see
     * this package's Conformance notes on {@code !base64}/{@code !base64url} being stricter than
     * {@code java.util.Base64}'s own decoder about it).
     */
    @Override
    public String write(byte[] value) {
        return switch (encoding) {
            case BASE64 -> Base64.getEncoder().encodeToString(value);
            case BASE64URL -> Base64.getUrlEncoder().encodeToString(value);
            case BASE32 -> Base32Decoding.encode(value);
            case HEX -> HEX_FORMAT.formatHex(value);
        };
    }

    private void validate(byte[] value, String text) {
        minLength.ifPresent(min -> {
            if (value.length < min) {
                throw new AtomValidationException(
                        "'" + text + "' decodes to " + value.length + " bytes, less than the minimum " + min);
            }
        });
        maxLength.ifPresent(max -> {
            if (value.length > max) {
                throw new AtomValidationException(
                        "'" + text + "' decodes to " + value.length + " bytes, more than the maximum " + max);
            }
        });
    }
}
