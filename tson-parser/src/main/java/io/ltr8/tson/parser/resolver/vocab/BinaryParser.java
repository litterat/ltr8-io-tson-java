package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.BinaryType;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Parses and validates against meta.tn1's {@code binary} constructor (§5.3's four binary atoms,
 * RFC 4648) -- one class, not one per encoding (an earlier version of this file *did* split it
 * into four sibling classes, one per encoding, since each encoding's decode algorithm is genuinely
 * different -- but that's the same shape of branching {@link IntegerParser} already does on {@code
 * size.signed()} and {@link FloatParser} already does on {@code format}, not a reason to fork the
 * class). Holds a {@link BinaryType} -- the pure constraint values, unchanged by this split --
 * rather than declaring those fields itself.
 */
public record BinaryParser(BinaryType constraints) implements AtomType<byte[]> {

    /** {@code base64 => !binary BASE64}, and so on for the other three -- §5.3's four built-in annotations, all unconstrained beyond {@code encoding}. */
    public static final BinaryParser BASE64 = new BinaryParser(BinaryType.BASE64);
    public static final BinaryParser BASE64URL = new BinaryParser(BinaryType.BASE64URL);
    public static final BinaryParser BASE32 = new BinaryParser(BinaryType.BASE32);
    public static final BinaryParser HEX = new BinaryParser(BinaryType.HEX);

    public BinaryParser(BinaryType.Encoding encoding, Optional<Integer> minLength, Optional<Integer> maxLength) {
        this(new BinaryType(encoding, minLength, maxLength));
    }

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    /** §5.3's built-in annotation name for this instance's encoding, e.g. {@code !base64}. */
    public String typeName() {
        return constraints.typeName();
    }

    @Override
    public byte[] read(TokenValue token) {
        String text = token.text();
        byte[] value = switch (constraints.encoding()) {
            case BASE64 -> Base64Decoding.decode(text, Base64.getDecoder(), constraints.encoding().typeName());
            case BASE64URL -> Base64Decoding.decode(text, Base64.getUrlDecoder(), constraints.encoding().typeName());
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
        return switch (constraints.encoding()) {
            case BASE64 -> Base64.getEncoder().encodeToString(value);
            case BASE64URL -> Base64.getUrlEncoder().encodeToString(value);
            case BASE32 -> Base32Decoding.encode(value);
            case HEX -> HEX_FORMAT.formatHex(value);
        };
    }

    private void validate(byte[] value, String text) {
        constraints.minLength().ifPresent(min -> {
            if (value.length < min) {
                throw new AtomValidationException(
                        "'" + text + "' decodes to " + value.length + " bytes, less than the minimum " + min);
            }
        });
        constraints.maxLength().ifPresent(max -> {
            if (value.length > max) {
                throw new AtomValidationException(
                        "'" + text + "' decodes to " + value.length + " bytes, more than the maximum " + max);
            }
        });
    }
}
