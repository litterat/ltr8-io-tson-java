package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.schema.meta.Ipv4Type;
import java.util.List;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.atom.CidrNetwork;
import io.ltr8.tson.schema.atom.InternetAddress;

import java.net.Inet4Address;
import java.net.UnknownHostException;

/**
 * meta-kernel's {@code ipv4_type} constructor (§5.5's {@code ipv4} atom, RFC 3986's {@code
 * IPv4address} production -- core.tn: "dotted-quad per the RFC 3986 IPv4address production").
 *
 * <p><b>Deliberately does not delegate parsing to {@link java.net.InetAddress}.</b> Confirmed
 * empirically before writing this: {@code InetAddress.ofLiteral} -- the no-DNS, literal-only entry
 * point added for exactly this kind of use -- is still far more lenient than RFC 3986's {@code
 * dec-octet} grammar. It silently accepts a leading zero ({@code "0177.0.0.1"} parses, RFC 3986
 * requires a single {@code DIGIT} with no leading zero for 0-9), the legacy BSD short/class-based
 * forms ({@code "1.2.3"} parses as {@code 1.2.0.3}, RFC 3986 requires exactly four dotted octets),
 * and even a bare 32-bit integer literal ({@code "3232235521"} parses as {@code 192.168.0.1}). This
 * is the same leniency class behind real-world SSRF-filter-bypass techniques, not merely a spec-
 * fidelity gap the way UUID/base64/date's JDK leniency was -- so this atom validates the token
 * against RFC 3986's {@code dec-octet} grammar itself, extracts the four octets directly from the
 * regex match, and constructs the address from raw bytes via {@link
 * java.net.InetAddress#getByAddress(byte[])} -- a pure bytes-to-object call with no parsing, no
 * reinterpretation, and (per its own Javadoc) no name-service lookup.
 *
 * <p>{@code within}/{@code excluding} (meta.tn's {@code ipv4_type}) are not modeled -- no built-in
 * instance sets either, and set-membership/non-overlap against an array of other addresses or CIDR
 * blocks is a materially bigger piece of work than a scalar constraint, left for later.
 */
public record Ipv4Parser(List<CidrNetwork> within, List<CidrNetwork> excluding)
        implements AtomType<Inet4Address> {

    /** §5.5's built-in annotation name -- {@code !ipv4}. */
    public static final String TYPENAME = "ipv4";

    /** {@code ipv4 => !ipv4_type {}} -- the unconstrained IPv4 address, §5.5's {@code !ipv4}. */
    public static final Ipv4Parser UNCONSTRAINED = new Ipv4Parser(List.of(), List.of());

    /**
     * The parser {@code constraints} asks for. A malformed entry in either list is refused before a reader is
     * ever
     * built ({@code Ipv4Type.coherenceCheck}), so reaching here with one is an internal fault rather than an
     * author error.
     */
    public static Ipv4Parser of(Ipv4Type constraints) {
        return new Ipv4Parser(networks(constraints.within()), networks(constraints.excluding()));
    }

    /**
     * The facet's text entries as networks. Every entry has already been checked to be one at schema load
     * ({@code Ipv4Type.coherenceCheck}), so a malformed entry cannot reach here; one that somehow did is dropped
     * rather than silently treated as matching nothing, and the schema-load check is where it is reported.
     */
    private static List<CidrNetwork> networks(List<String> entries) {
        return entries.stream()
                .map(entry -> CidrNetwork.parse(entry, 32))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public Inet4Address read(TokenValue token) {
        String text = token.text();
        byte[] octets = tryParseOctets(text);
        if (octets == null) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid IPv4 address -- expected RFC 3986's dotted-quad IPv4address "
                            + "production, no leading zeros or non-canonical forms (§5.5)", "an IPv4 address");
        }
        checkNetworks(text, octets);
        return (Inet4Address) toInetAddress(octets);
    }

    /**
     * {@code getHostAddress()}, not {@code toString()} -- confirmed empirically that {@code
     * Inet4Address#toString()} prepends a stray {@code /} (a leftover from {@code InetAddress}'s
     * combined hostname-plus-address design), which {@code getHostAddress()} doesn't.
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
    public String write(Inet4Address value) {
        return value.getHostAddress();
    }

    /** Returns the 4 decoded octets, or {@code null} if {@code text} doesn't match the grammar. */
    static byte[] tryParseOctets(String text) {
        return InternetAddress.ipv4(text);
    }

    static java.net.InetAddress toInetAddress(byte[] addressBytes) {
        try {
            return java.net.InetAddress.getByAddress(addressBytes);
        } catch (UnknownHostException e) {
            // Unreachable: getByAddress(byte[]) only throws for an address of the wrong length.
            throw new IllegalStateException(e);
        }
    }
}
