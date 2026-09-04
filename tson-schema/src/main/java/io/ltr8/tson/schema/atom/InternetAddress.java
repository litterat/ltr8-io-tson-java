package io.ltr8.tson.schema.atom;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The IPv4 and IPv6 address grammars, as pure text-to-octets functions.
 *
 * <p><b>Why a grammar lives in the value model, when {@link Rational} and {@link Complex} carry none.</b>
 * Those two are reached by a facet that is already a host value: {@code rational_type.min} binds to a
 * {@code Rational}, so a constraint check compares values and never sees text. An address family's
 * {@code within}/{@code excluding} cannot be: the facets are typed {@code [value]} in meta.tn, and they must
 * stay that way, because they list <em>networks</em> and meta declares no network instance to type them by --
 * core.tn does, and core imports meta, so the dependency runs the wrong way.
 *
 * <p>So those facets arrive as text, and the family that owns the rule is the only place that can judge them.
 * A check somewhere else -- in the linker, or the resolver -- would be a second home for one family's rule,
 * which is the thing {@code Atom.coherenceCheck} exists to prevent. The grammar comes here so the rule can
 * stay where it belongs.
 *
 * <p><b>Null-returning, never throwing.</b> Each caller names the value it was reading in its own message: a
 * reader says "not a valid IPv4 address", a coherence check says "{@code within} lists something that is not
 * a network". A shared exception would flatten those into one.
 */
public final class InternetAddress {

    private InternetAddress() {
    }

    private static final String DEC_OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9][0-9]|[0-9])";

    /**
     * RFC 3986's {@code IPv4address} production, strictly: no leading zeros, no non-canonical forms. Reused
     * by {@link #ipv6} for RFC 4291 §2.2's embedded IPv4-tail form, so the two cannot drift apart.
     */
    private static final Pattern IPV4_ADDRESS = Pattern.compile(
            DEC_OCTET + "\\." + DEC_OCTET + "\\." + DEC_OCTET + "\\." + DEC_OCTET);

    private static final Pattern HEX_GROUP = Pattern.compile("[0-9A-Fa-f]{1,4}");

    /** The four octets of {@code text}, or {@code null} where it is not an IPv4 address. */
    public static byte[] ipv4(String text) {
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

    /** The sixteen octets of {@code text}, or {@code null} where it is not an RFC 4291 §2.2 address. */
    public static byte[] ipv6(String text) {
        int compressionAt = text.indexOf("::");
        boolean compressed = compressionAt >= 0;
        String before = compressed ? text.substring(0, compressionAt) : text;
        String after = compressed ? text.substring(compressionAt + 2) : "";
        if (compressed && after.indexOf("::") >= 0) {
            return null;
        }

        String[] beforeGroups = splitGroups(before);
        String[] afterGroups = splitGroups(after);
        for (String group : beforeGroups) {
            if (group.isEmpty()) {
                return null;
            }
        }
        for (String group : afterGroups) {
            if (group.isEmpty()) {
                return null;
            }
        }

        // The IPv4-tail form is only recognised as the address's very last group -- either the last group
        // before "::" when there's no compression at all, or the last group after "::" when there is. A dot
        // anywhere else is simply an invalid hex group.
        boolean ipv4TailInBefore = !compressed && beforeGroups.length > 0
                && beforeGroups[beforeGroups.length - 1].indexOf('.') >= 0;
        boolean ipv4TailInAfter = compressed && afterGroups.length > 0
                && afterGroups[afterGroups.length - 1].indexOf('.') >= 0;

        int beforeHexCount = beforeGroups.length - (ipv4TailInBefore ? 1 : 0);
        int afterHexCount = afterGroups.length - (ipv4TailInAfter ? 1 : 0);
        int ipv4Slots = (ipv4TailInBefore || ipv4TailInAfter) ? 2 : 0;
        int explicitSlots = beforeHexCount + afterHexCount + ipv4Slots;

        if (compressed) {
            // "::" must stand for at least one group of zeros -- otherwise it is redundant and ambiguous
            // with the non-compressed preferred form.
            if (explicitSlots >= 8) {
                return null;
            }
        } else if (explicitSlots != 8) {
            return null;
        }

        byte[] result = new byte[16];
        int offset = 0;
        for (int i = 0; i < beforeHexCount; i++) {
            offset = writeHexGroup(result, offset, beforeGroups[i]);
            if (offset < 0) {
                return null;
            }
        }
        if (ipv4TailInBefore) {
            offset = writeIpv4Tail(result, offset, beforeGroups[beforeGroups.length - 1]);
            if (offset < 0) {
                return null;
            }
        }
        if (compressed) {
            offset += (8 - explicitSlots) * 2; // already zero-initialised
        }
        for (int i = 0; i < afterHexCount; i++) {
            offset = writeHexGroup(result, offset, afterGroups[i]);
            if (offset < 0) {
                return null;
            }
        }
        if (ipv4TailInAfter) {
            offset = writeIpv4Tail(result, offset, afterGroups[afterGroups.length - 1]);
            if (offset < 0) {
                return null;
            }
        }
        return result;
    }

    /** {@code octets} as dotted-quad text. */
    public static String ipv4Text(byte[] octets) {
        StringBuilder text = new StringBuilder(15);
        for (int i = 0; i < octets.length; i++) {
            if (i > 0) {
                text.append('.');
            }
            text.append(octets[i] & 0xFF);
        }
        return text.toString();
    }

    /**
     * {@code octets} in RFC 5952's canonical form: lowercase hex, no leading zeros in a group, and the
     * longest run of two or more zero groups replaced by {@code ::} -- the leftmost such run where two tie.
     *
     * <p>Canonical rather than the uncompressed eight-group spelling, because this is what a network writes
     * back through: {@code 2001:db8::/32} returning as {@code 2001:db8:0:0:0:0:0:0/32} would be legal and
     * useless. A non-canonical input is still normalised -- {@code 2001:0db8:0000:...} comes back compressed
     * -- which is what it means for the value to be the octets rather than the text.
     */
    public static String ipv6Text(byte[] octets) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((octets[i * 2] & 0xFF) << 8) | (octets[i * 2 + 1] & 0xFF);
        }
        // RFC 5952 §5: an IPv4-mapped address keeps its dotted tail, which is the spelling it was written
        // in and the one a reader of it expects back.
        if (groups[0] == 0 && groups[1] == 0 && groups[2] == 0 && groups[3] == 0 && groups[4] == 0
                && groups[5] == 0xFFFF) {
            return "::ffff:" + ipv4Text(java.util.Arrays.copyOfRange(octets, 12, 16));
        }
        int bestStart = -1;
        int bestLength = 1; // a run of one is never compressed, per RFC 5952 §4.2.2
        for (int i = 0; i < 8; i++) {
            if (groups[i] != 0) {
                continue;
            }
            int end = i;
            while (end < 8 && groups[end] == 0) {
                end++;
            }
            if (end - i > bestLength) {
                bestStart = i;
                bestLength = end - i;
            }
            i = end;
        }
        StringBuilder text = new StringBuilder(39);
        for (int i = 0; i < 8; i++) {
            if (bestStart >= 0 && i == bestStart) {
                // Always both colons: the run may end the address, and there is then no group after it to
                // supply the second one -- which is how `2001:db8::` came out as `2001:db8:`.
                text.append("::");
                i += bestLength - 1;
                continue;
            }
            if (text.length() > 0 && text.charAt(text.length() - 1) != ':') {
                text.append(':');
            }
            text.append(Integer.toHexString(groups[i]));
        }
        return text.toString();
    }

    private static String[] splitGroups(String s) {
        return s.isEmpty() ? new String[0] : s.split(":", -1);
    }

    /** The next offset, or {@code -1} where the group is not four hex digits or fewer. */
    private static int writeHexGroup(byte[] result, int offset, String group) {
        if (!HEX_GROUP.matcher(group).matches()) {
            return -1;
        }
        int value = Integer.parseInt(group, 16);
        result[offset] = (byte) (value >> 8);
        result[offset + 1] = (byte) value;
        return offset + 2;
    }

    private static int writeIpv4Tail(byte[] result, int offset, String group) {
        byte[] octets = ipv4(group);
        if (octets == null) {
            return -1;
        }
        System.arraycopy(octets, 0, result, offset, 4);
        return offset + 4;
    }
}
