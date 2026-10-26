package io.ltr8.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * A host as RFC 3987's {@code ihost} names one, profiled to DNS: a {@link HostName}, an IPv4 address or an IPv6
 * address -- what a listener binds and what an allowlist names. One value, never both: a name never equals an
 * address.
 *
 * <p><b>Each member keeps its own equality.</b> A name compares by name, an address by its octets, so {@code ::1}
 * and {@code 0:0:0:0:0:0:0:1} are one value; the two address families never equal each other, so
 * {@code 127.0.0.1} and its IPv4-mapped {@code ::ffff:127.0.0.1} are two. {@link #text()} is the member's canonical
 * text: the name's U-labels, a dotted-quad, RFC 5952 text.
 *
 * <p><b>Brackets are a second spelling of an IPv6 address.</b> A URI brackets one so that it can find the port
 * after it, and operators copy {@code [::1]} out of URLs; the address is the value, so {@code [::1]} and
 * {@code ::1} are one, written back unbracketed. A zone identifier ({@code fe80::1%eth0}) is refused, as is any
 * percent-encoding.
 */
public sealed interface Host permits HostName, Host.Address {

    /** The member's canonical text. */
    String text();

    /** The host {@code text} names: a bracketed or bare IPv6 address, a dotted-quad, or a host name. */
    static Host parse(String text) {
        if (text.startsWith("[") || text.indexOf(':') >= 0) {
            String inner = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
            if (inner.indexOf('%') >= 0) {
                throw new HostSyntaxException("carries a zone identifier, which is local to one machine and not "
                        + "part of a host", text);
            }
            byte[] octets = text.startsWith("[") && !text.endsWith("]") ? null : InternetAddress.ipv6(inner);
            if (octets == null) {
                throw new HostSyntaxException("is not an IPv6 address, and a host holding ':' or '[' must be one",
                        text);
            }
            return new Address(ipv6(octets));
        }
        byte[] octets = InternetAddress.ipv4(text);
        if (octets != null) {
            return new Address(ipv4(octets));
        }
        if (isDottedDigits(text)) {
            throw new HostSyntaxException("is not an IPv4 address, and a host name's last label begins with a "
                    + "letter", text);
        }
        return HostName.parse(text);
    }

    /** An IPv4 or IPv6 address as a host, compared by its family and octets. */
    record Address(InetAddress address) implements Host {

        public Address {
            if (!(address instanceof Inet4Address) && !(address instanceof Inet6Address)) {
                throw new IllegalArgumentException("not an IPv4 or IPv6 address: " + address);
            }
        }

        @Override
        public String text() {
            byte[] octets = address.getAddress();
            return address instanceof Inet4Address ? InternetAddress.ipv4Text(octets)
                    : InternetAddress.ipv6Text(octets);
        }

        /** The canonical text, as {@link #text()}. */
        @Override
        public String toString() {
            return text();
        }
    }

    private static boolean isDottedDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == '.' || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return !text.isEmpty();
    }

    private static InetAddress ipv4(byte[] octets) {
        try {
            return InetAddress.getByAddress(octets);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Always an {@link Inet6Address}: {@code InetAddress.getByAddress} turns an IPv4-mapped one into IPv4. */
    private static InetAddress ipv6(byte[] octets) {
        try {
            return Inet6Address.getByAddress(null, octets, -1);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
