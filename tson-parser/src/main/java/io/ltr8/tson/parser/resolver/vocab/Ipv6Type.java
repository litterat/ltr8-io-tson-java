package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;

import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * meta-kernel's {@code ipv6_type} constructor (§5.5's {@code ipv6} atom, RFC 4291 §2.2's text
 * representation -- core.tn1: "IPv6 address, RFC 4291 §2.2 text representation. Zone identifiers
 * (RFC 4007, {@code fe80::1%eth0}) are host-local and excluded from the contract.").
 *
 * <p>Same reasoning as {@link Ipv4Type}: this does not hand the token's text to {@code
 * InetAddress}'s own parsing at all. RFC 4291 §2.2's grammar includes an alternative form for the
 * last 32 bits, {@code x:x:x:x:x:x:d.d.d.d}, embedding an IPv4 dotted-quad tail -- handing that
 * whole string to a JDK parser would silently reintroduce {@link Ipv4Type}'s exact leniency gap
 * (leading zeros, short forms) through the back door of that tail. This class parses the full RFC
 * 4291 §2.2 grammar itself -- the 8-group preferred form, at most one {@code ::} run-of-zeros
 * compression, and an optional dotted-quad tail validated against the same strict {@link
 * Ipv4Type#IPV4_ADDRESS} grammar -- and builds the address from raw bytes via {@code
 * InetAddress.getByAddress(byte[])}, never a JDK text parser.
 *
 * <p>Zone identifiers ({@code %eth0}) need no special-case rejection: {@code %} simply isn't in
 * this grammar's character set, so a zone suffix fails as an ordinary malformed group -- matching
 * core.tn1's exclusion of them from the contract.
 *
 * <p>Unlike {@link Ipv4Type}'s decimal octets, a hex group's leading zeros are not rejected --
 * RFC 4291 §2.2 defines a group as "one to four hexadecimal digits", a digit *count* restriction,
 * not a leading-zero prohibition the way RFC 3986's decimal {@code dec-octet} has one; {@code
 * "0000:0000:0000:0000:0000:0000:0000:0001"} is exactly as valid as {@code "::1"}, just not
 * canonical form (RFC 5952 governs canonical *output*, not input acceptance).
 *
 * <p>{@code within}/{@code excluding} (meta.tn1's {@code ipv6_type}) are not modeled, for the same
 * reason as {@link Ipv4Type}: deferred, not scoped out.
 *
 * <p><b>Deliberately uses {@code Inet6Address.getByAddress(String, byte[], int)}, not the generic
 * {@code InetAddress.getByAddress(byte[])} that {@link Ipv4Type} uses.</b> Confirmed empirically:
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
public record Ipv6Type() implements AtomType<Inet6Address> {

    /** §5.5's built-in annotation name -- {@code !ipv6}. */
    public static final String TYPENAME = "ipv6";

    /** {@code ipv6 => !ipv6_type {}} -- the unconstrained IPv6 address, §5.5's {@code !ipv6}. */
    public static final Ipv6Type UNCONSTRAINED = new Ipv6Type();

    private static final Pattern HEX_GROUP = Pattern.compile("[0-9a-fA-F]{1,4}");

    @Override
    public Inet6Address read(TokenValue token) {
        String text = token.text();
        byte[] bytes = parse(text);
        try {
            return Inet6Address.getByAddress(null, bytes, -1);
        } catch (UnknownHostException e) {
            // Unreachable: getByAddress(host, byte[16], scope) only throws for the wrong length.
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code getHostAddress()}, not {@code toString()} -- same reason as {@link Ipv4Type#write}.
     * Writes the uncompressed, full 8-group form ({@code getHostAddress()} doesn't apply RFC 5952's
     * {@code ::} canonicalization) -- still valid per {@link #read}'s own grammar, just not the
     * shortest legal spelling; canonicalizing isn't needed for round-tripping to work.
     */
    @Override
    public String write(Inet6Address value) {
        return value.getHostAddress();
    }

    private static byte[] parse(String text) {
        int compressionAt = text.indexOf("::");
        boolean compressed = compressionAt >= 0;
        String before = compressed ? text.substring(0, compressionAt) : text;
        String after = compressed ? text.substring(compressionAt + 2) : "";
        if (compressed && after.indexOf("::") >= 0) {
            throw malformed(text);
        }

        String[] beforeGroups = splitGroups(before);
        String[] afterGroups = splitGroups(after);
        for (String group : beforeGroups) {
            if (group.isEmpty()) {
                throw malformed(text);
            }
        }
        for (String group : afterGroups) {
            if (group.isEmpty()) {
                throw malformed(text);
            }
        }

        // The IPv4-tail form is only recognised as the address's very last group -- either the
        // last group before "::" when there's no compression at all, or the last group after
        // "::" when there is. A dot anywhere else is simply an invalid hex group.
        boolean ipv4TailInBefore = !compressed && beforeGroups.length > 0
                && beforeGroups[beforeGroups.length - 1].indexOf('.') >= 0;
        boolean ipv4TailInAfter = compressed && afterGroups.length > 0
                && afterGroups[afterGroups.length - 1].indexOf('.') >= 0;

        int beforeHexCount = beforeGroups.length - (ipv4TailInBefore ? 1 : 0);
        int afterHexCount = afterGroups.length - (ipv4TailInAfter ? 1 : 0);
        int ipv4Slots = (ipv4TailInBefore || ipv4TailInAfter) ? 2 : 0;
        int explicitSlots = beforeHexCount + afterHexCount + ipv4Slots;

        if (compressed) {
            if (explicitSlots >= 8) {
                // "::" must stand for at least one group of zeros -- otherwise it's redundant
                // and ambiguous with the non-compressed preferred form.
                throw malformed(text);
            }
        } else if (explicitSlots != 8) {
            throw malformed(text);
        }

        byte[] result = new byte[16];
        int offset = 0;
        for (int i = 0; i < beforeHexCount; i++) {
            offset = writeHexGroup(result, offset, beforeGroups[i], text);
        }
        if (ipv4TailInBefore) {
            offset = writeIpv4Tail(result, offset, beforeGroups[beforeGroups.length - 1], text);
        }
        if (compressed) {
            offset += (8 - explicitSlots) * 2; // already zero-initialised
        }
        for (int i = 0; i < afterHexCount; i++) {
            offset = writeHexGroup(result, offset, afterGroups[i], text);
        }
        if (ipv4TailInAfter) {
            offset = writeIpv4Tail(result, offset, afterGroups[afterGroups.length - 1], text);
        }
        return result;
    }

    private static String[] splitGroups(String s) {
        return s.isEmpty() ? new String[0] : s.split(":", -1);
    }

    private static int writeHexGroup(byte[] result, int offset, String group, String fullText) {
        if (!HEX_GROUP.matcher(group).matches()) {
            throw malformed(fullText);
        }
        int value = Integer.parseInt(group, 16);
        result[offset] = (byte) (value >> 8);
        result[offset + 1] = (byte) value;
        return offset + 2;
    }

    private static int writeIpv4Tail(byte[] result, int offset, String group, String fullText) {
        byte[] octets = Ipv4Type.tryParseOctets(group);
        if (octets == null) {
            throw malformed(fullText);
        }
        System.arraycopy(octets, 0, result, offset, 4);
        return offset + 4;
    }

    private static AtomParseException malformed(String text) {
        return new AtomParseException(
                "'" + text + "' is not a valid IPv6 address -- expected RFC 4291 §2.2's text representation (§5.5)");
    }
}
