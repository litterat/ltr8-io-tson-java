package io.ltr8.tson.base.atom;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An IPv6 CIDR network -- 16 prefix octets and a prefix length of 0 to 128. What {@code cidr6} reads to,
 * and what a {@code cidr6} or {@code ipv6} facet names in {@code within} and {@code excluding}.
 *
 * <p>See {@link CidrNetwork} for why the two families are separate types and for the value semantics both
 * share; the arithmetic itself is {@code CidrBits}, which this delegates to over its own octets.
 */
public record CidrInet6Network(byte[] prefix, int prefixLength) implements CidrNetwork {

    /** Family width in bits -- 16 octets, so a prefix length past 128 names no network. */
    public static final int FAMILY_BITS = 128;

    public CidrInet6Network {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.length != 16) {
            throw new IllegalArgumentException(
                    "an IPv6 network has 16 prefix octets, not " + prefix.length);
        }
        if (prefixLength < 0 || prefixLength > FAMILY_BITS) {
            throw new IllegalArgumentException("prefix length " + prefixLength + " is outside 0-" + FAMILY_BITS);
        }
        prefix = prefix.clone();
    }

    /** {@code text} as an IPv6 network, or {@code null} where it is not one -- see {@link CidrNetwork#parse}. */
    public static CidrInet6Network parse(String text) {
        return (CidrInet6Network) CidrNetwork.parse(text, FAMILY_BITS);
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
        return other instanceof CidrInet6Network network
                && network.prefixLength >= prefixLength
                && CidrBits.contains(prefix, prefixLength, network.prefix);
    }

    @Override
    public List<CidrNetwork> halves() {
        // Split first: a single address has no halves, and CidrBits says so in those words where building
        // the lower half would instead refuse a prefix length one past the family's width.
        byte[] upper = CidrBits.upperHalf(prefix, prefixLength);
        return List.of(new CidrInet6Network(prefix, prefixLength + 1), new CidrInet6Network(upper, prefixLength + 1));
    }

    @Override
    public String text() {
        return InternetAddress.ipv6Text(prefix) + "/" + prefixLength;
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
        return other instanceof CidrInet6Network network
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
