package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.BytesType;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Parses and validates against meta.tn's {@code binary} constructor (§5.3's four binary atoms,
 * RFC 4648) -- one class, not one per encoding (an earlier version of this file *did* split it
 * into four sibling classes, one per encoding, since each encoding's decode algorithm is genuinely
 * different -- but that's the same shape of branching {@link IntegerParser} already does on {@code
 * size.signed()} and {@link FloatParser} already does on {@code format}, not a reason to fork the
 * class). Holds a {@link BytesType} -- the pure constraint values, unchanged by this split --
 * rather than declaring those fields itself.
 */
public record BytesParser(BytesType constraints) implements AtomType<byte[]> {

    /**
     * Part 1's one binary tag, {@code !bytes}, and the alphabet it reads in: base64 (§4, padded). A
     * schemaless document has no type to carry a selector, so there is nothing for a reader to consult and
     * no way one spelling could be more right than another.
     */
    public static final BytesParser BASE64 = new BytesParser(BytesType.UNCONSTRAINED);

    /** The alphabet this parser reads and writes -- the type's own selector. */
    public BytesType.Encoding encoding() {
        return constraints.encoding();
    }

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    /**
     * Part 1's built-in annotation name -- {@code !bytes}, and only that.
     *
     * <p>The four alphabets are not built-in type annotations. A schemaless document has no schema to carry
     * a {@code @bytes_encoding} directive, so there is nothing for a reader to consult and no way for one
     * spelling to be more right than another: Part 1 fixes base64 and offers no override. Under a schema
     * the directive decides, which is where the choice belongs -- and it means {@code !bytes} names one type
     * in both classes rather than one type in Part 2 and four in Part 1.
     */
    public static final String TYPENAME = "bytes";

    @Override
    public byte[] read(TokenValue token) {
        String text = token.text();
        byte[] value = switch (encoding()) {
            case BASE64 -> Base64Decoding.decode(text, Base64.getDecoder(), "base64");
            case BASE64URL -> Base64Decoding.decode(text, Base64.getUrlDecoder(), "base64url");
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
            throw new AtomParseException("'" + text + "' is not valid hex (RFC 4648 §8, §5.3): " + e.getMessage(),
                    "a hex encoding");
        }
    }

    /**
     * Encodes with padding, always -- the inverse of {@link #read}'s own padding requirement (see
     * this package's Conformance notes on {@code !base64}/{@code !base64url} being stricter than
     * {@code java.util.Base64}'s own decoder about it).
     */
    @Override
    public String write(byte[] value) {
        return switch (encoding()) {
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
                        "'" + text + "' decodes to " + value.length + " bytes, less than the minimum " + min,
                        "at least " + min + " bytes");
            }
        });
        constraints.maxLength().ifPresent(max -> {
            if (value.length > max) {
                throw new AtomValidationException(
                        "'" + text + "' decodes to " + value.length + " bytes, more than the maximum " + max,
                        "at most " + max + " bytes");
            }
        });
        // Decoded octets, never characters of the spelling: the same value is 4 characters of hex and 4 of
        // base64 for different byte counts, so a length counted on the token would mean a different type
        // per alphabet.
        constraints.length().ifPresent(exact -> {
            if (value.length != exact) {
                throw new AtomValidationException(
                        "'" + text + "' decodes to " + value.length + " bytes, not the required " + exact,
                        "exactly " + exact + " bytes");
            }
        });
    }
}
