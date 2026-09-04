package io.ltr8.tson.schema.atom;

import java.util.Arrays;
import java.util.Objects;

/**
 * A CIDR network -- the host type {@code cidr4} and {@code cidr6} read to, and the value {@code within} and
 * {@code excluding} are judged in.
 *
 * <p><b>A value type, like {@link Rational}.</b> Two spellings of one network are one value: equality is over
 * the prefix octets and the prefix length, never over the text that carried them. That is what lets a schema
 * compare the networks a facet names without caring how they were written.
 *
 * <p><b>Containment needs no interval arithmetic.</b> CIDR blocks are nodes of a prefix tree, so two are
 * nested or disjoint and never partially overlapping -- which is why {@link #overlaps} is exactly "one
 * contains the other". And the comparisons are over the prefix bits of two equal-length octet arrays, so
 * IPv4's four bytes and IPv6's sixteen need one implementation: only the length differs, and it arrives with
 * the value.
 *
 * <p><b>Host bits are a separate question</b> ({@link #hostBitsAreZero}). {@code 10.1.0.0/8} parses -- it is
 * well-formed text naming an address and a prefix -- and fails §5.5's rule that a network's every bit past the
 * prefix is zero. Keeping the two apart is what lets a caller report a malformed token and a violated
 * constraint differently, which is the distinction a reader's two exception types exist for.
 */
public record CidrNetwork(byte[] prefix, int prefixLength) {

    private static final int MAX_PREFIX_DIGITS = 3;

    public CidrNetwork {
        prefix = prefix.clone();
    }

    /**
     * {@code text} as a network of the family {@code familyBits} names (32 or 128), or {@code null} where it
     * is not one -- a malformed address, or a prefix outside the family's range.
     *
     * <p><b>Host bits are not judged here</b>, and deliberately: §5.5 makes nonzero host bits a rule about a
     * value that <em>is</em> a network rather than about whether the text is one, so a caller reports it as a
     * violated constraint and not as a malformed token. {@link #hostBitsAreZero} is the question.
     *
     * <p>Null-returning rather than throwing so each caller names what it was reading: a reader refuses a
     * value, a coherence check refuses a facet entry, and the two want different words.
     */
    public static CidrNetwork parse(String text, int familyBits) {
        int slash = text.indexOf('/');
        if (slash < 0 || text.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        int prefixLength = prefixLength(text.substring(slash + 1));
        if (prefixLength < 0 || prefixLength > familyBits) {
            return null;
        }
        String address = text.substring(0, slash);
        byte[] octets = familyBits == 32 ? InternetAddress.ipv4(address) : InternetAddress.ipv6(address);
        if (octets == null) {
            return null;
        }
        return new CidrNetwork(octets, prefixLength);
    }

    /** Whether {@code address} lies inside this network -- its leading {@link #prefixLength} bits match. */
    public boolean contains(byte[] address) {
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

    /**
     * Whether {@code other} lies wholly inside this network -- it is at least as specific, a longer or equal
     * prefix, and its own prefix address falls inside.
     */
    public boolean contains(CidrNetwork other) {
        return other.prefixLength >= prefixLength && contains(other.prefix);
    }

    /** Whether the two share any address, which for prefix-tree nodes means one contains the other. */
    public boolean overlaps(CidrNetwork other) {
        return contains(other) || other.contains(this);
    }

    /** This network in CIDR text -- the family's address form, a {@code /}, and the prefix length. */
    public String text() {
        String address = prefix.length == 4 ? InternetAddress.ipv4Text(prefix) : InternetAddress.ipv6Text(prefix);
        return address + "/" + prefixLength;
    }

    @Override
    public String toString() {
        return text();
    }

    /** Value equality over the octets, which an array component would otherwise give by identity. */
    @Override
    public boolean equals(Object other) {
        return other instanceof CidrNetwork network
                && prefixLength == network.prefixLength
                && Arrays.equals(prefix, network.prefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(prefix), prefixLength);
    }

    @Override
    public byte[] prefix() {
        return prefix.clone();
    }

    /**
     * The decimal prefix length after the {@code /}, or {@code -1} if the text is not one. Leading zeros are
     * rejected for the same reason a {@code dec-octet} rejects them: {@code /8} and {@code /08} would
     * otherwise be two spellings of one network, which is the confusable-input class strictness shuts down.
     */
    private static int prefixLength(String text) {
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

    /**
     * §5.5's network rule: every bit past the prefix is zero, since the value denotes a block rather than an
     * address inside one. Bit-at-a-time rather than byte-masked -- at most 128 iterations, and no boundary
     * case to get wrong.
     */
    public boolean hostBitsAreZero() {
        return hostBitsAreZero(prefix, prefixLength);
    }

    private static boolean hostBitsAreZero(byte[] address, int prefixLength) {
        for (int bit = prefixLength; bit < address.length * 8; bit++) {
            if ((address[bit / 8] & (0x80 >> (bit % 8))) != 0) {
                return false;
            }
        }
        return true;
    }
}
