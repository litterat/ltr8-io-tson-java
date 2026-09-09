package io.ltr8.tson.base.atom;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An IPv4 CIDR network -- 4 prefix octets and a prefix length of 0 to 32. What {@code cidr4} reads to,
 * and what a {@code cidr4} or {@code ipv4} facet names in {@code within} and {@code excluding}.
 *
 * <p>See {@link CidrNetwork} for why the two families are separate types and for the value semantics both
 * share; the arithmetic itself is {@code CidrBits}, which this delegates to over its own octets.
 */
public record CidrInet4Network(byte[] prefix, int prefixLength) implements CidrNetwork {

    /** Family width in bits -- 4 octets, so a prefix length past 32 names no network. */
    public static final int FAMILY_BITS = 32;

    public CidrInet4Network {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.length != 4) {
            throw new IllegalArgumentException(
                    "an IPv4 network has 4 prefix octets, not " + prefix.length);
        }
        if (prefixLength < 0 || prefixLength > FAMILY_BITS) {
            throw new IllegalArgumentException("prefix length " + prefixLength + " is outside 0-" + FAMILY_BITS);
        }
        prefix = prefix.clone();
    }

    /** {@code text} as an IPv4 network, or {@code null} where it is not one -- see {@link CidrNetwork#parse}. */
    public static CidrInet4Network parse(String text) {
        return (CidrInet4Network) CidrNetwork.parse(text, FAMILY_BITS);
    }

    @Override
    public int familyBits() {
        return FAMILY_BITS;
    }

    @Override
    public boolean contains(byte[] address) {
        return CidrBits.contains(prefix, prefixLength, address);
    }

    @Override
    public boolean contains(CidrNetwork other) {
        return other instanceof CidrInet4Network network
                && network.prefixLength >= prefixLength
                && CidrBits.contains(prefix, prefixLength, network.prefix);
    }

    @Override
    public List<CidrNetwork> halves() {
        // Split first: a single address has no halves, and CidrBits says so in those words where building
        // the lower half would instead refuse a prefix length one past the family's width.
        byte[] upper = CidrBits.upperHalf(prefix, prefixLength);
        return List.of(new CidrInet4Network(prefix, prefixLength + 1), new CidrInet4Network(upper, prefixLength + 1));
    }

    @Override
    public String text() {
        return InternetAddress.ipv4Text(prefix) + "/" + prefixLength;
    }

    @Override
    public boolean hostBitsAreZero() {
        return CidrBits.hostBitsAreZero(prefix, prefixLength);
    }

    @Override
    public String toString() {
        return text();
    }

    /** Value equality over the octets, which an array component would otherwise give by identity. */
    @Override
    public boolean equals(Object other) {
        return other instanceof CidrInet4Network network
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
}
