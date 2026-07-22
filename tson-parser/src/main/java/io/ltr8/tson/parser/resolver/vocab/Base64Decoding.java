package io.ltr8.tson.parser.resolver.vocab;

import java.util.Base64;

/**
 * Decode logic for {@link BinaryType}'s {@code BASE64}/{@code BASE64URL} encodings -- both use
 * {@link java.util.Base64}'s {@code Decoder} directly, differing only in which alphabet's decoder
 * {@link BinaryType} passes in, so the one real decision (padding strictness) lives here once.
 *
 * <p>{@link Base64.Decoder} accepts missing padding outright -- {@code "TWE"} decodes identically
 * to the correctly-padded {@code "TWE="} (confirmed empirically before writing this). RFC 4648 §3.2:
 * "Implementations MUST include appropriate pad characters at the end of encoded data unless the
 * specification referring to this document explicitly states otherwise" -- §5.3 doesn't state
 * otherwise, so padding is treated as required here: {@link #decode} rejects any input whose length
 * isn't a multiple of 4 before ever reaching the JDK decoder, which alone wouldn't catch this. See
 * {@code SPEC-FEEDBACK.md} #10 for why this needed a judgment call at all.
 *
 * <p>Not similarly strict about non-canonical trailing padding bits ({@code "TR=="}, whose last
 * character's low bits should be zero and aren't, per RFC 4648 §3.5) -- that section says decoders
 * MAY reject such input, not MUST, so the JDK's default leniency there is left alone.
 */
final class Base64Decoding {

    private Base64Decoding() {
    }

    static byte[] decode(String text, Base64.Decoder decoder, String schemeName) {
        if (text.length() % 4 != 0) {
            throw new AtomParseException("'" + text + "' is not a valid " + schemeName
                    + " encoding -- length must be a multiple of 4 once padded (RFC 4648, §5.3)");
        }
        try {
            return decoder.decode(text);
        } catch (IllegalArgumentException e) {
            throw new AtomParseException(
                    "'" + text + "' is not valid " + schemeName + " (RFC 4648, §5.3): " + e.getMessage());
        }
    }
}
