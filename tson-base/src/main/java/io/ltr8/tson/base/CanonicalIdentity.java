package io.ltr8.tson.base;

import io.ltr8.net.Host;
import io.ltr8.net.HostName;
import io.ltr8.net.HostSyntaxException;
import io.ltr8.net.Iri;
import io.ltr8.net.IriSyntaxException;
import io.ltr8.unicode.Nfc;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * [TSON-DATA] §2.2.1's canonical identity: how a schema is named. An identity is an IRI, split into a host and a
 * path; the scheme is a transport hint and the query verification metadata, so both are removed, and what remains
 * -- the host's canonical text plus the path -- is the identity, compared byte for byte.
 *
 * <p><b>The host is a {@link Host} value</b> (SPEC-FEEDBACK.md #4, #6): RFC 3987's {@code ihost} with a DNS name.
 * A registered name must be a {@link HostName}, an IP literal an IPv6 address (no IPvFuture, no zone), and the
 * host's canonical text is the value's -- lowercase U-labels, a dotted-quad, RFC 5952 text in brackets -- so
 * {@code Example.COM} and {@code example.com} are one identity, as are {@code xn--bcher-kva.example} and
 * {@code bücher.example}, and {@code [::1]} and {@code [0:0:0:0:0:0:0:1]}.
 *
 * <p><b>The path is NFC</b>, and its URI spelling is read back: each percent-encoded UTF-8 sequence of a character
 * beyond US-ASCII is that character, and hex case is not significant, so {@code sch%c3%a9mas} and {@code schémas}
 * are one path. What has no IRI form stays an error -- a percent-encoding that is not the UTF-8 of an IRI
 * character ({@code %FF}, an encoded bidi control), an encoded character that is not NFC -- as does
 * percent-encoding an unreserved ASCII character, a dot-segment, userinfo, a port and a fragment. A
 * percent-encoded reserved character ({@code %2F}) is part of the identity, written in uppercase hex.
 *
 * <p><b>Two entry points, because a document's own name has one spelling.</b> {@link #canonicalize} reads a
 * <em>reference</em> -- {@code !!schema}, {@code !!import}, a source's key, a request path -- in any spelling of
 * its identity. {@link #validate} judges a document's own {@code !!id}, which must already be written in canonical
 * form: the canonical host, the path with no character beyond US-ASCII percent-encoded, reserved encodings in
 * uppercase hex. Every published document then has one spelling of its name. {@link #toUri} is the other
 * direction, a reference's URI spelling for a wire that carries US-ASCII only -- a fetch, an HTTP field.
 *
 * <p><b>An identity without a host has an absolute path.</b> A path-only or {@code file:}-style reference has the
 * path alone as its identity, resolved only against a library entry. A relative path would then be a string a host
 * and path also spell -- {@code tson.io/2026/38/m/core.tn} written without its scheme is
 * {@code https://tson.io/2026/38/m/core.tn}'s identity -- so the path must begin with {@code /}, and the two kinds
 * of identity are disjoint by their first character. {@code /local/orders.tn}, {@code file:/local/orders.tn} and
 * {@code file:///local/orders.tn} are one identity.
 *
 * <p><b>Public, and part of the contract of every identity-bearing seam.</b> {@code TsonSchemaLoader.load} takes a
 * canonical identity, and a {@code SchemaSource} is asked for a document by one, so anything implementing either
 * derives and compares identities exactly the way the library does -- which is this class. The half of §2.2.1 that
 * reads the {@code ?sha256=} pin this one strips lives in {@code TsonContentHash}. The methods return plain
 * {@code String}s: a canonical identity is a map key throughout the registries.
 */
public final class CanonicalIdentity {

    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private CanonicalIdentity() {
    }

    /**
     * The canonical identity a reference names, in any spelling of it -- scheme and query removed, the host read
     * as a {@link Host} value and the path read back from its URI spelling. {@code http://} and {@code https://}
     * spellings name one identity, and a {@code ?sha256=} pin does not distinguish a pinned reference from a
     * plain one.
     *
     * @throws SchemaValidationException if {@code reference} names no identity
     */
    public static String canonicalize(String reference) {
        return read(reference).identity();
    }

    /**
     * Judges a document's own name: {@code id} must name an identity and be written in its canonical form, so a
     * published document has one spelling of its name.
     *
     * @throws SchemaValidationException if {@code id} names no identity, or names one in another spelling
     */
    public static void validate(String id) {
        Reading reading = read(id);
        if (!reading.written().equals(reading.identity())) {
            throw new SchemaValidationException("'" + id + "' is not written in canonical form, which a document's "
                    + "own !!id must be -- write '" + reading.canonicalSpelling(id) + "'");
        }
    }

    /**
     * Whether two references name one identity -- {@link #canonicalize} applied to both, then compared.
     *
     * @throws SchemaValidationException if either names no identity
     */
    public static boolean sameIdentity(String reference, String otherReference) {
        return canonicalize(reference).equals(canonicalize(otherReference));
    }

    /**
     * {@code reference}'s URI spelling (RFC 3987 §3.1), scheme and query kept: the host by ToASCII, each character
     * of the path beyond US-ASCII as its percent-encoded UTF-8. The spelling a wire carrying US-ASCII only needs;
     * it names the identity {@code reference} does.
     *
     * @throws SchemaValidationException if {@code reference} names no identity
     */
    public static String toUri(String reference) {
        Reading reading = read(reference);
        StringBuilder uri = new StringBuilder();
        reading.iri().scheme().ifPresent(scheme -> uri.append(scheme).append(':'));
        if (reading.iri().authority().isPresent()) {
            uri.append("//").append(reading.asciiHost());
        }
        percentEncodeBeyondAscii(uri, reading.path());
        reading.iri().query().ifPresent(query -> uri.append('?').append(query));
        return uri.toString();
    }

    /**
     * A reference, read: the canonical identity, the same parts as the reference wrote them (so {@link #validate}
     * can tell a canonical spelling from another), the host's ASCII form, and the canonical path.
     */
    private record Reading(Iri iri, String identity, String written, String asciiHost, String path) {

        String canonicalSpelling(String original) {
            StringBuilder spelling = new StringBuilder();
            iri.scheme().ifPresent(scheme -> spelling.append(scheme).append(':'));
            if (iri.authority().isPresent()) {
                spelling.append("//");
            }
            spelling.append(identity);
            iri.query().ifPresent(query -> spelling.append('?').append(query));
            return spelling.toString();
        }
    }

    private static Reading read(String reference) {
        Iri iri;
        try {
            iri = Iri.parse(reference, Iri.Grammar.IRI);
        } catch (IriSyntaxException e) {
            throw new SchemaValidationException(
                    "'" + reference + "' is not a valid IRI-reference (RFC 3987): it " + e.reason());
        }
        if (iri.fragment().isPresent()) {
            throw new SchemaValidationException(
                    "'" + reference + "' carries a fragment, not permitted in an identifying URI");
        }
        Iri.Authority authority = iri.authority().orElse(null);
        String writtenHost = authority == null ? "" : writtenHost(authority.host());
        if (writtenHost.isEmpty() && !iri.path().startsWith("/")) {
            throw new SchemaValidationException("'" + reference + "' has no host and a path that is not absolute: "
                    + "an identity without a host names a library entry by an absolute path, so that it can never "
                    + "be one a host and path also spell");
        }
        String host = "";
        String asciiHost = "";
        if (authority != null) {
            if (authority.userinfo().isPresent()) {
                throw new SchemaValidationException(
                        "'" + reference + "' carries userinfo, not permitted in an identifying URI");
            }
            if (authority.port().isPresent()) {
                throw new SchemaValidationException(
                        "'" + reference + "' carries a port, not permitted in an identifying URI");
            }
            if (!writtenHost.isEmpty()) {
                Host value = host(reference, authority.host());
                host = identityText(value);
                asciiHost = value instanceof HostName name ? name.ascii() : host;
            }
        }
        String path = path(reference, iri.path());
        return new Reading(iri, host + path, writtenHost + iri.path(), asciiHost, path);
    }

    /** The host as the reference wrote it, an IPv6 literal in its brackets as the IRI grammar has them. */
    private static String writtenHost(Iri.Host host) {
        return host.kind() == Iri.Host.Kind.IPV6 || host.kind() == Iri.Host.Kind.IP_FUTURE
                ? "[" + host.text() + "]" : host.text();
    }

    private static Host host(String reference, Iri.Host written) {
        if (written.kind() == Iri.Host.Kind.IP_FUTURE) {
            throw new SchemaValidationException("'" + reference + "' has an IPvFuture host, which no identity can "
                    + "hold: an identity's host is a host name or an IPv4 or IPv6 address");
        }
        try {
            return Host.parse(written.kind() == Iri.Host.Kind.IPV6 ? "[" + written.text() + "]" : written.text());
        } catch (HostSyntaxException e) {
            throw new SchemaValidationException("'" + reference + "' has the host '" + e.text() + "', which "
                    + e.reason());
        }
    }

    /** The host's canonical text as an identity spells it: an IPv6 address in the brackets the IRI grammar needs. */
    private static String identityText(Host host) {
        return host instanceof Host.Address address && address.address() instanceof java.net.Inet6Address
                ? "[" + host.text() + "]" : host.text();
    }

    /**
     * The path read back from its URI spelling: each percent-encoded UTF-8 sequence of an IRI character beyond
     * US-ASCII decoded, every other encoding kept in uppercase hex, and the result required NFC.
     */
    private static String path(String reference, String written) {
        for (String segment : written.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new SchemaValidationException("'" + reference + "' contains a dot-segment in its path");
            }
        }
        StringBuilder path = new StringBuilder(written.length());
        for (int i = 0; i < written.length(); ) {
            char c = written.charAt(i);
            if (c != '%') {
                path.append(c);
                i++;
                continue;
            }
            int octet = octet(reference, written, i);
            if (octet < 0x80) {
                if (UNRESERVED.indexOf((char) octet) >= 0) {
                    throw new SchemaValidationException(
                            "'" + reference + "' percent-encodes the unreserved character '" + (char) octet + "'");
                }
                path.append('%').append(HEX[octet >> 4]).append(HEX[octet & 0xF]);
                i += 3;
                continue;
            }
            int end = i;
            ByteBuffer bytes = ByteBuffer.allocate(written.length());
            while (end < written.length() && written.charAt(end) == '%' && octet(reference, written, end) >= 0x80) {
                bytes.put((byte) octet(reference, written, end));
                end += 3;
            }
            bytes.flip();
            String decoded;
            try {
                decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
            } catch (CharacterCodingException e) {
                throw new SchemaValidationException("'" + reference + "' percent-encodes '"
                        + written.substring(i, end) + "', which is not the UTF-8 of any character, so the "
                        + "reference has no IRI form");
            }
            decoded.codePoints().forEach(cp -> {
                if (!Iri.isUcschar(cp) || isBidiFormatting(cp)) {
                    throw new SchemaValidationException(("'%s' percent-encodes U+%04X, which an IRI cannot hold as "
                            + "itself, so the reference has no IRI form").formatted(reference, cp));
                }
            });
            path.append(decoded);
            i = end;
        }
        String result = path.toString();
        if (!Nfc.of(result).equals(result)) {
            throw new SchemaValidationException("'" + reference + "' has a path not in Normalization Form C -- the "
                    + "path is '" + Nfc.of(result) + "'");
        }
        return result;
    }

    /** The octet a {@code %XX} triplet at {@code at} encodes, hex digits in either case. */
    private static int octet(String reference, String text, int at) {
        if (at + 2 >= text.length()) {
            throw new SchemaValidationException("'" + reference + "' has a malformed percent-encoding");
        }
        int high = Character.digit(text.charAt(at + 1), 16);
        int low = Character.digit(text.charAt(at + 2), 16);
        if (high < 0 || low < 0) {
            throw new SchemaValidationException("'" + reference + "' has a malformed percent-encoding");
        }
        return (high << 4) | low;
    }

    /** RFC 3987 §4.1's bidi formatting characters, which an IRI MUST NOT hold. */
    private static boolean isBidiFormatting(int cp) {
        return cp == 0x200E || cp == 0x200F || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)
                || cp == 0x061C;
    }

    private static void percentEncodeBeyondAscii(StringBuilder out, String path) {
        for (int i = 0; i < path.length(); ) {
            int cp = path.codePointAt(i);
            if (cp < 0x80) {
                out.append((char) cp);
            } else {
                for (byte b : Character.toString(cp).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            }
            i += Character.charCount(cp);
        }
    }
}
