package io.ltr8.tson.base.atom;

/**
 * The prefix arithmetic {@link CidrInet4Network} and {@link CidrInet6Network} share, over the raw octets
 * rather than over {@code prefix()}, whose defensive copy would otherwise be made at every step of a walk
 * that only reads.
 *
 * <p>One implementation serves both because only the width differs and it arrives with the value -- the
 * argument {@link CidrNetwork} makes, stated here as the code that makes it true.
 */
final class CidrBits {

    private static final int MAX_PREFIX_DIGITS = 3;

    private CidrBits() {
    }

    static boolean contains(byte[] prefix, int prefixLength, byte[] address) {
        if (address.length != prefix.length) {
            return false;
        }
        int wholeBytes = prefixLength / 8;
        for (int i = 0; i < wholeBytes; i++) {
            if (address[i] != prefix[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (address[wholeBytes] & mask) == (prefix[wholeBytes] & mask);
    }

    /** The upper half's prefix octets -- the same address with the bit past the prefix set. */
    static byte[] upperHalf(byte[] prefix, int prefixLength) {
        if (prefixLength >= prefix.length * 8) {
            throw new IllegalStateException(
                    "a /" + prefixLength + " network is a single address and has no halves");
        }
        byte[] upper = prefix.clone();
        upper[prefixLength / 8] |= (byte) (0x80 >> (prefixLength % 8));
        return upper;
    }

    /** Bit-at-a-time rather than byte-masked -- at most 128 iterations, and no boundary case to get wrong. */
    static boolean hostBitsAreZero(byte[] prefix, int prefixLength) {
        for (int bit = prefixLength; bit < prefix.length * 8; bit++) {
            if ((prefix[bit / 8] & (0x80 >> (bit % 8))) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The decimal prefix length after the {@code /}, or {@code -1} if the text is not one. Leading zeros are
     * rejected for the same reason a {@code dec-octet} rejects them: {@code /8} and {@code /08} would
     * otherwise be two spellings of one network, which is the confusable-input class strictness shuts down.
     */
    static int prefixLength(String text) {
        if (text.isEmpty() || text.length() > MAX_PREFIX_DIGITS) {
            return -1;
        }
        if (text.length() > 1 && text.charAt(0) == '0') {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
