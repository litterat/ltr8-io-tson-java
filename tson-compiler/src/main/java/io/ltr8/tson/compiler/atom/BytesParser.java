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
public record BytesParser(Encoding encoding, BytesType constraints) implements AtomType<byte[]> {

    /**
     * The RFC 4648 base encodings a text encoding may spell a {@code bytes} value in -- meta.tn's
     * {@code base_encoding}. It lives here rather than on {@link BytesType} because it is not part of
     * the type: it is what a *reader* was told, from Part 1's own {@code !hex}/{@code !base64} tags in
     * a schemaless document, or from the {@code @bytes_encoding} directive under a schema.
     */
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
    public static final BytesParser BASE64 = new BytesParser(Encoding.BASE64, BytesType.UNCONSTRAINED);
    public static final BytesParser BASE64URL = new BytesParser(Encoding.BASE64URL, BytesType.UNCONSTRAINED);
    public static final BytesParser BASE32 = new BytesParser(Encoding.BASE32, BytesType.UNCONSTRAINED);
    public static final BytesParser HEX = new BytesParser(Encoding.HEX, BytesType.UNCONSTRAINED);

    /** The alphabet an unannotated position takes -- §4, padded, and what every neighbouring format chose. */
    public static final Encoding DEFAULT = Encoding.BASE64;

    public BytesParser(Encoding encoding, Optional<Integer> minLength, Optional<Integer> maxLength) {
        this(encoding, new BytesType(Optional.empty(), minLength, maxLength));
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
        return switch (encoding) {
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
