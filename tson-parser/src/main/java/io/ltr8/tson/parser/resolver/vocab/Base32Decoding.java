package io.ltr8.tson.parser.resolver.vocab;

/**
 * Decode and encode logic for {@link BinaryParser}'s {@code BASE32} encoding -- RFC 4648 §6, the one
 * binary encoding with no JDK support at all ({@link java.util.Base64} covers §4/§5, {@link
 * java.util.HexFormat} covers §8), so this is from scratch both ways: 5 bits per character against
 * the canonical uppercase alphabet {@code ABCDEFGHIJKLMNOPQRSTUVWXYZ234567}, accumulated into (or
 * out of) an 8-bit-aligned byte stream.
 *
 * <p>Case-sensitive (uppercase only) -- unlike hex, RFC 4648 doesn't establish case-insensitivity as
 * a universal decode convention for base32's alphabet, and meta.tn1 says only that "encoding
 * alphabets are pinned to RFC 4648" with no mention of case flexibility; a lowercase input is
 * rejected rather than silently accepted.
 *
 * <p>Padding ({@code =}) is required to a multiple of 8 characters, with the count of trailing
 * padding characters restricted to RFC 4648 §6's table -- {@code 0}/{@code 1}/{@code 3}/{@code 4}/
 * {@code 6} (corresponding to 5/4/3/2/1 data bytes in the final block); {@code 2}/{@code 5}/{@code 7}
 * are never valid regardless of what they'd arithmetically decode to. Like {@link Base64Decoding},
 * not strict about non-canonical trailing bits within the last partial byte (RFC 4648 §3.5 makes
 * rejecting those optional, not required).
 */
final class Base32Decoding {

    private Base32Decoding() {
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** Legal counts of trailing {@code =} padding characters in a canonical (length-multiple-of-8) base32 encoding. */
    private static final boolean[] LEGAL_PADDING_COUNT = {true, true, false, true, true, false, true, false};

    static byte[] decode(String text) {
        if (text.length() % 8 != 0) {
            throw new AtomParseException("'" + text + "' is not a valid base32 encoding -- "
                    + "length must be a multiple of 8 once padded (RFC 4648 §6, §5.3)");
        }

        int padding = 0;
        while (padding < text.length() && text.charAt(text.length() - 1 - padding) == '=') {
            padding++;
        }
        // An all-padding string (e.g. "========") counts up past the table entirely -- bounds-check
        // before indexing rather than let it throw ArrayIndexOutOfBoundsException.
        if (padding >= LEGAL_PADDING_COUNT.length || !LEGAL_PADDING_COUNT[padding]) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid base32 encoding -- " + padding + " padding characters is not a legal count (RFC 4648 §6)");
        }

        int dataChars = text.length() - padding;
        byte[] output = new byte[dataChars * 5 / 8];
        long buffer = 0;
        int bitsInBuffer = 0;
        int outputIndex = 0;
        for (int i = 0; i < dataChars; i++) {
            char c = text.charAt(i);
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new AtomParseException("'" + text + "' is not a valid base32 encoding -- "
                        + "'" + c + "' is not in the base32 alphabet (RFC 4648 §6)");
            }
            buffer = (buffer << 5) | value;
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8;
                output[outputIndex++] = (byte) ((buffer >> bitsInBuffer) & 0xFF);
            }
        }
        return output;
    }

    /** The exact inverse of {@link #decode}: pads to a length-multiple-of-8 with {@code =}. */
    static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        long buffer = 0;
        int bitsInBuffer = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsInBuffer += 8;
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5;
                sb.append(ALPHABET.charAt((int) ((buffer >> bitsInBuffer) & 0x1F)));
            }
        }
        if (bitsInBuffer > 0) {
            int value = (int) ((buffer << (5 - bitsInBuffer)) & 0x1F);
            sb.append(ALPHABET.charAt(value));
        }
        while (sb.length() % 8 != 0) {
            sb.append('=');
        }
        return sb.toString();
    }
}
