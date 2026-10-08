package io.ltr8.net;

import java.util.List;

/**
 * A CIDR network (RFC 4632 for IPv4, RFC 4291 §2.3 for IPv6): a prefix and how many of its leading bits are fixed.
 *
 * <p><b>A type per family</b> -- {@link CidrInet4Network} and {@link CidrInet6Network} -- because a declared type
 * is how a caller says which family it wants, as {@code Inet4Address} and {@code Inet6Address} do; this sealed
 * interface is what the two share.
 *
 * <p><b>A value type.</b> Two spellings of one network are one value: equality is over the prefix octets and the
 * prefix length, never over the text that carried them. Two networks of different families are never equal.
 *
 * <p><b>Containment needs no interval arithmetic.</b> CIDR blocks are nodes of a prefix tree, so two are
 * nested or disjoint and never partially overlapping -- which is why {@link #overlaps} is exactly "one
 * contains the other". And the comparisons are over the prefix bits of two equal-length octet arrays, so
 * IPv4's four bytes and IPv6's sixteen need one implementation: only the length differs, and it arrives with
 * the value. That implementation is {@code CidrBits}, which both members delegate to over their own octets.
 *
 * <p><b>Host bits are a separate question</b> ({@link #hostBitsAreZero}). {@code 10.1.0.0/8} parses -- it is
 * well-formed text naming an address and a prefix -- and is not a network in the strict sense, whose every bit
 * past the prefix is zero. Keeping the two apart is what lets a caller report a malformed token and a violated
 * constraint differently, which is the distinction a reader's two exception types exist for.
 */
public sealed interface CidrNetwork permits CidrInet4Network, CidrInet6Network {

    /** This network's prefix octets -- four for IPv4, sixteen for IPv6. A copy; the value is immutable. */
    byte[] prefix();

    /** How many leading bits of {@link #prefix()} the network fixes. */
    int prefixLength();

    /** The family's address width in bits -- 32 or 128, and what a prefix length is bounded by. */
    int familyBits();

    /**
     * {@code text} as a network of the family {@code familyBits} names (32 or 128), or {@code null} where it
     * is not one -- a malformed address, or a prefix outside the family's range.
     *
     * <p><b>Host bits are not judged here</b>, and deliberately: nonzero host bits are a rule about a value that
     * <em>is</em> a network rather than about whether the text is one, so a caller can report it as a violated
     * constraint rather than as malformed text. {@link #hostBitsAreZero} is the question.
     *
     * <p>Null-returning rather than throwing so each caller names what it was reading in its own words.
     */
    static CidrNetwork parse(String text, int familyBits) {
        int slash = text.indexOf('/');
        if (slash < 0 || text.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        int prefixLength = CidrBits.prefixLength(text.substring(slash + 1));
        if (prefixLength < 0 || prefixLength > familyBits) {
            return null;
        }
        String address = text.substring(0, slash);
        byte[] octets = familyBits == 32 ? InternetAddress.ipv4(address) : InternetAddress.ipv6(address);
        return octets == null ? null : of(octets, prefixLength);
    }

    /** The member of this family whose width {@code prefix} has -- four octets or sixteen. */
    static CidrNetwork of(byte[] prefix, int prefixLength) {
        return prefix.length == 4
                ? new CidrInet4Network(prefix, prefixLength)
                : new CidrInet6Network(prefix, prefixLength);
    }

    /** The whole of the family {@code familyBits} names -- a zero prefix of length zero. */
    static CidrNetwork all(int familyBits) {
        return of(new byte[familyBits / 8], 0);
    }

    /** Whether {@code address} lies inside this network -- its leading {@link #prefixLength} bits match. */
    boolean contains(byte[] address);

    /**
     * Whether {@code other} lies wholly inside this network -- it is at least as specific, a longer or equal
     * prefix, and its own prefix address falls inside. A network of the other family never is.
     */
    boolean contains(CidrNetwork other);

    /**
     * This network's two children in the prefix tree -- the same addresses, split at one more bit. RFC 4632's
     * subnetting, and the step a set-difference walk descends by: an exclusion strictly inside this network
     * lies wholly in one half or the other, never across both, which is what makes the walk a partition
     * rather than a search.
     *
     * @throws IllegalStateException if this network is a single address, which has no halves
     */
    List<CidrNetwork> halves();

    /** Whether the two share any address, which for prefix-tree nodes means one contains the other. */
    default boolean overlaps(CidrNetwork other) {
        return contains(other) || other.contains(this);
    }

    /** This network in CIDR text -- the family's address form, a {@code /}, and the prefix length. */
    String text();

    /**
     * Whether every bit past the prefix is zero, so the value denotes a block rather than an address inside one.
     */
    boolean hostBitsAreZero();
}
