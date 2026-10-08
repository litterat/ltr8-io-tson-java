package io.ltr8.net;

/**
 * RFC 9542's EUI-48 text form, as a pure text-to-octets function: six pairs of hex digits separated consistently
 * by {@code :} or by {@code -}. The two separators are alternatives rather than a character class, so a mixed
 * spelling ({@code AA-BB:CC-DD:EE-FF}) is not an address.
 *
 * <p>Null-returning, never throwing, as {@link InternetAddress} is: each caller names the value it was reading
 * in its own message.
 */
public final class MacAddress {

    private MacAddress() {
    }

    /** The six octets of {@code text}, or {@code null} where it is not an EUI-48 address. */
    public static byte[] eui48(String text) {
        if (text.length() != 17) {
            return null;
        }
        char separator = text.charAt(2);
        if (separator != ':' && separator != '-') {
            return null;
        }
        byte[] octets = new byte[6];
        for (int i = 0; i < 6; i++) {
            int at = i * 3;
            if (i > 0 && text.charAt(at - 1) != separator) {
                return null;
            }
            if (!isAsciiHex(text.charAt(at)) || !isAsciiHex(text.charAt(at + 1))) {
                return null;
            }
            octets[i] = (byte) (Character.digit(text.charAt(at), 16) << 4 | Character.digit(text.charAt(at + 1), 16));
        }
        return octets;
    }

    /** {@link Character#digit} also reads the full-width and other Unicode digits; a hex octet is ASCII. */
    private static boolean isAsciiHex(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }
}
