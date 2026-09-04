package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.schema.meta.Ipv6Type;
import java.util.List;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.atom.CidrNetwork;
import io.ltr8.tson.schema.atom.InternetAddress;

import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * meta-kernel's {@code ipv6_type} constructor (§5.5's {@code ipv6} atom, RFC 4291 §2.2's text
 * representation -- core.tn: "IPv6 address, RFC 4291 §2.2 text representation. Zone identifiers
 * (RFC 4007, {@code fe80::1%eth0}) are host-local and excluded from the contract.").
 *
 * <p>Same reasoning as {@link Ipv4Parser}: this does not hand the token's text to {@code
 * InetAddress}'s own parsing at all. RFC 4291 §2.2's grammar includes an alternative form for the
 * last 32 bits, {@code x:x:x:x:x:x:d.d.d.d}, embedding an IPv4 dotted-quad tail -- handing that
 * whole string to a JDK compiler would silently reintroduce {@link Ipv4Parser}'s exact leniency gap
 * (leading zeros, short forms) through the back door of that tail. This class parses the full RFC
 * 4291 §2.2 grammar itself -- the 8-group preferred form, at most one {@code ::} run-of-zeros
 * compression, and an optional dotted-quad tail validated against the same strict grammar a bare IPv4
 * address gets -- and builds the address from raw bytes via {@code InetAddress.getByAddress(byte[])}, never a
 * JDK text compiler. The grammar itself lives in {@link io.ltr8.tson.schema.atom.InternetAddress}, so that
 * the families that must judge a {@code within} entry can reach it; this class is the reader over it.
 *
 * <p>Zone identifiers ({@code %eth0}) need no special-case rejection: {@code %} simply isn't in
 * this grammar's character set, so a zone suffix fails as an ordinary malformed group -- matching
 * core.tn's exclusion of them from the contract.
 *
 * <p>Unlike {@link Ipv4Parser}'s decimal octets, a hex group's leading zeros are not rejected --
 * RFC 4291 §2.2 defines a group as "one to four hexadecimal digits", a digit *count* restriction,
 * not a leading-zero prohibition the way RFC 3986's decimal {@code dec-octet} has one; {@code
 * "0000:0000:0000:0000:0000:0000:0000:0001"} is exactly as valid as {@code "::1"}, just not
 * canonical form (RFC 5952 governs canonical *output*, not input acceptance).
 *
 * <p>{@code within}/{@code excluding} (meta.tn's {@code ipv6_type}) are not modeled, for the same
 * reason as {@link Ipv4Parser}: deferred, not scoped out.
 *
 * <p><b>Deliberately uses {@code Inet6Address.getByAddress(String, byte[], int)}, not the generic
 * {@code InetAddress.getByAddress(byte[])} that {@link Ipv4Parser} uses.</b> Confirmed empirically:
 * for a 16-byte array in the IPv4-mapped range (the top 80 bits zero, next 16 bits all-ones -- the
 * exact shape produced by an input like {@code "::ffff:192.0.2.1"}), the generic method silently
 * returns an {@code Inet4Address} instead, not an {@code Inet6Address} -- the same value ends up as
 * a different, mutually non-{@code equals} Java type depending on which narrow sub-range it falls
 * in, which would break this atom's "one consistent host representation" contract for what's still
 * unambiguously an RFC 4291 §2.2 IPv6 text token. The scoped-address constructor with {@code
 * scope_id = -1} (confirmed empirically to behave like "no scope" -- {@code getScopeId()} reads back
 * {@code 0} with no {@code %0} zone suffix in {@code toString()}, and it {@code equals()} the
 * generic method's result for every non-mapped address tried) sidesteps the JDK's own
 * auto-downcast entirely.
 */
public record Ipv6Parser(List<CidrNetwork> within, List<CidrNetwork> excluding)
        implements AtomType<Inet6Address> {

    /** §5.5's built-in annotation name -- {@code !ipv6}. */
    public static final String TYPENAME = "ipv6";

    /** {@code ipv6 => !ipv6_type {}} -- the unconstrained IPv6 address, §5.5's {@code !ipv6}. */
    public static final Ipv6Parser UNCONSTRAINED = new Ipv6Parser(List.of(), List.of());

    /**
     * The parser {@code constraints} asks for. A malformed entry in either list is refused before a reader is
     * ever
     * built ({@code Ipv6Type.coherenceCheck}), so reaching here with one is an internal fault rather than an
     * author error.
     */
    public static Ipv6Parser of(Ipv6Type constraints) {
        return new Ipv6Parser(networks(constraints.within()), networks(constraints.excluding()));
    }

    /**
     * The facet's text entries as networks. Every entry has already been checked to be one at schema load
     * ({@code Ipv6Type.coherenceCheck}), so a malformed entry cannot reach here; one that somehow did is dropped
     * rather than silently treated as matching nothing, and the schema-load check is where it is reported.
     */
    private static List<CidrNetwork> networks(List<String> entries) {
        return entries.stream()
                .map(entry -> CidrNetwork.parse(entry, 128))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static final Pattern HEX_GROUP = Pattern.compile("[0-9a-fA-F]{1,4}");

    @Override
    public Inet6Address read(TokenValue token) {
        String text = token.text();
        byte[] bytes = InternetAddress.ipv6(text);
        if (bytes == null) {
            throw malformed(text);
        }
        checkNetworks(text, bytes);
        try {
            return Inet6Address.getByAddress(null, bytes, -1);
        } catch (UnknownHostException e) {
            // Unreachable: getByAddress(host, byte[16], scope) only throws for the wrong length.
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code getHostAddress()}, not {@code toString()} -- same reason as {@link Ipv4Parser#write}.
     * Writes the uncompressed, full 8-group form ({@code getHostAddress()} doesn't apply RFC 5952's
     * {@code ::} canonicalization) -- still valid per {@link #read}'s own grammar, just not the
     * shortest legal spelling; canonicalizing isn't needed for round-tripping to work.
     */

    /**
     * §5.5's {@code within}/{@code excluding}: the address must lie inside at least one {@code within}
     * network when the list is non-empty, and inside none of {@code excluding}. As meta.tn puts it,
     * "{@code excluding} carves holes out of {@code within}".
     *
     * <p>The lists are parsed once, when the reader is compiled, so a read is bit comparisons over the octets
     * it already has.
     */
    private void checkNetworks(String text, byte[] octets) {
        if (!within.isEmpty() && within.stream().noneMatch(network -> network.contains(octets))) {
            throw new AtomValidationException("'" + text + "' lies inside none of this type's permitted "
                    + "networks", "an address within one of " + within.size() + " permitted network"
                    + (within.size() == 1 ? "" : "s"));
        }
        if (excluding.stream().anyMatch(network -> network.contains(octets))) {
            throw new AtomValidationException("'" + text + "' lies inside a network this type excludes",
                    "an address outside every excluded network");
        }
    }

    @Override
    public String write(Inet6Address value) {
        return value.getHostAddress();
    }

    /**
     * The 16 address bytes, or {@code null} if {@code text} isn't an RFC 4291 §2.2 address -- {@link
     * Cidr6Parser} reuses this whole grammar for the address half of a network, the same way {@link
     * Cidr4Parser} reuses {@link Ipv4Parser#tryParseOctets}. Null-returning rather than throwing because the
     * caller's own token is the network, not the address, and it names that in its own message.
     */
    static byte[] tryParseBytes(String text) {
        return InternetAddress.ipv6(text);
    }

    private static AtomParseException malformed(String text) {
        return new AtomParseException(
                "'" + text + "' is not a valid IPv6 address -- expected RFC 4291 §2.2's text representation (§5.5)",
                "an IPv6 address");
    }
}
