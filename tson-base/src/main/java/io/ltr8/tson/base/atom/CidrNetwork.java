package io.ltr8.tson.base.atom;

import java.util.List;

/**
 * A CIDR network -- what {@code cidr4} and {@code cidr6} read to, and the value {@code within} and
 * {@code excluding} are judged in.
 *
 * <p><b>A family each, because a host type is how a component names what it wants.</b> A schemaless read has
 * no schema to say which family a position holds, so it asks which built-in produces the class the component
 * declares ({@code HostAtoms}) -- and a class two families produce answers nothing. {@link CidrInet4Network}
 * and {@link CidrInet6Network} are one family each, so a component naming either is read the way an
 * {@code Inet4Address} component already is. Naming this interface instead is honestly ambiguous and is
 * refused as such: a sealed type with two members binds as a union, which wants a type annotation in the
 * document, or a bridge registered against it.
 *
 * <p><b>A value type, like {@link Rational}.</b> Two spellings of one network are one value: equality is over
 * the prefix octets and the prefix length, never over the text that carried them. That is what lets a schema
 * compare the networks a facet names without caring how they were written. Two networks of <em>different</em>
 * families are never equal, and now cannot be compared by accident either.
 *
 * <p><b>Containment needs no interval arithmetic.</b> CIDR blocks are nodes of a prefix tree, so two are
 * nested or disjoint and never partially overlapping -- which is why {@link #overlaps} is exactly "one
 * contains the other". And the comparisons are over the prefix bits of two equal-length octet arrays, so
 * IPv4's four bytes and IPv6's sixteen need one implementation: only the length differs, and it arrives with
 * the value. That implementation is {@code CidrBits}, which both members delegate to over their own octets.
 *
 * <p><b>Host bits are a separate question</b> ({@link #hostBitsAreZero}). {@code 10.1.0.0/8} parses -- it is
 * well-formed text naming an address and a prefix -- and fails §5.5's rule that a network's every bit past the
 * prefix is zero. Keeping the two apart is what lets a caller report a malformed token and a violated
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
     * <p><b>Host bits are not judged here</b>, and deliberately: §5.5 makes nonzero host bits a rule about a
     * value that <em>is</em> a network rather than about whether the text is one, so a caller reports it as a
     * violated constraint and not as a malformed token. {@link #hostBitsAreZero} is the question.
     *
     * <p>Null-returning rather than throwing so each caller names what it was reading: a reader refuses a
     * value, a coherence check refuses a facet entry, and the two want different words.
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
     * §5.5's network rule: every bit past the prefix is zero, since the value denotes a block rather than an
     * address inside one.
     */
    boolean hostBitsAreZero();
}
