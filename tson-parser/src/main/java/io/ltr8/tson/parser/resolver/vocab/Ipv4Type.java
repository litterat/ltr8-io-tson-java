package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * meta-kernel's {@code ipv4_type} constructor (§5.5's {@code ipv4} atom, RFC 3986's {@code
 * IPv4address} production -- core.tn1: "dotted-quad per the RFC 3986 IPv4address production").
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
 * <p>{@code within}/{@code excluding} (meta.tn1's {@code ipv4_type}) are not modeled -- no built-in
 * instance sets either, and set-membership/non-overlap against an array of other addresses or CIDR
 * blocks is a materially bigger piece of work than a scalar constraint, left for later.
 */
public record Ipv4Type() implements AtomType<Inet4Address> {

    /** §5.5's built-in annotation name -- {@code !ipv4}. */
    public static final String TYPENAME = "ipv4";

    /** {@code ipv4 => !ipv4_type {}} -- the unconstrained IPv4 address, §5.5's {@code !ipv4}. */
    public static final Ipv4Type UNCONSTRAINED = new Ipv4Type();

    private static final String DEC_OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])";

    /**
     * Package-visible, not {@code private}: {@link Ipv6Type} reuses this exact grammar for RFC
     * 4291 §2.2's embedded IPv4-tail form ({@code x:x:x:x:x:x:d.d.d.d}) -- the same strict
     * dec-octet check, not a second, potentially-drifting copy of it.
     */
    static final Pattern IPV4_ADDRESS = Pattern.compile(
            DEC_OCTET + "\\." + DEC_OCTET + "\\." + DEC_OCTET + "\\." + DEC_OCTET);

    @Override
    public Inet4Address read(TokenValue token) {
        String text = token.text();
        byte[] octets = tryParseOctets(text);
        if (octets == null) {
            throw new AtomParseException(
                    "'" + text + "' is not a valid IPv4 address -- expected RFC 3986's dotted-quad IPv4address "
                            + "production, no leading zeros or non-canonical forms (§5.5)");
        }
        return (Inet4Address) toInetAddress(octets);
    }

    /**
     * {@code getHostAddress()}, not {@code toString()} -- confirmed empirically that {@code
     * Inet4Address#toString()} prepends a stray {@code /} (a leftover from {@code InetAddress}'s
     * combined hostname-plus-address design), which {@code getHostAddress()} doesn't.
     */
    @Override
    public String write(Inet4Address value) {
        return value.getHostAddress();
    }

    /** Returns the 4 decoded octets, or {@code null} if {@code text} doesn't match the grammar. */
    static byte[] tryParseOctets(String text) {
        Matcher matcher = IPV4_ADDRESS.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        byte[] octets = new byte[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = (byte) Integer.parseInt(matcher.group(i + 1));
        }
        return octets;
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
