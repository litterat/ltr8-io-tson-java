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
import java.util.Optional;

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
 * <p><b>The query is hash parameters only.</b> {@link #declaredSha256} reads the {@code ?sha256=} pin a reference
 * declares and refuses a query parameter that is not a hash algorithm, or a pin that is not 64 lowercase hex
 * digits; {@code TsonContentHash} verifies a document against it. {@link #canonicalize} strips the query unread.
 *
 * <p><b>Public, and part of the contract of every identity-bearing seam.</b> {@code TsonSchemaLoader.load} takes a
 * canonical identity, and a {@code SchemaSource} is asked for a document by one, so anything implementing either
 * derives and compares identities exactly the way the library does -- which is this class. The methods return
 * plain {@code String}s: a canonical identity is a map key throughout the registries. {@link Identity} is the
 * same reading as a value, for the {@code identity} atom; both read through one method here.
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
        return reading(reference).identity().text();
    }

    /**
     * Judges a document's own name: {@code id} must name an identity and be written in its canonical form, so a
     * published document has one spelling of its name.
     *
     * @throws SchemaValidationException if {@code id} names no identity, or names one in another spelling
     */
    public static void validate(String id) {
        Reading reading = reading(id);
        if (!reading.written().equals(reading.identity().text())) {
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
        Reading reading = reading(reference);
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
     * The {@code sha256} content-hash pin {@code reference} declares on its query ({@code ?sha256=<hex>}), or empty
     * if it carries no query or an empty one. A query holds hash parameters only ([TSON-DATA] §2.2.1), and
     * {@code sha256}'s value is 64 lowercase hex digits.
     *
     * @throws IdentitySyntaxException if the query holds a parameter that is not a hash algorithm, or a malformed
     *     {@code sha256} value -- never silently retained
     */
    public static Optional<String> declaredSha256(String reference) {
        int q = reference.indexOf('?');
        if (q < 0 || q == reference.length() - 1) {
            return Optional.empty();
        }
        String sha256 = null;
        for (String param : reference.substring(q + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            if (!name.equals("sha256")) {
                throw new IdentitySyntaxException("carries the query parameter '" + name + "', and a "
                        + "content-address query may contain only hash-algorithm parameters", reference);
            }
            String value = eq < 0 ? "" : param.substring(eq + 1);
            if (!isFullLowercaseHex(value)) {
                throw new IdentitySyntaxException("carries the malformed sha256 pin '" + value + "' -- expected 64 "
                        + "lowercase hex digits", reference);
            }
            sha256 = value;
        }
        return Optional.ofNullable(sha256);
    }

    private static boolean isFullLowercaseHex(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A reference, read: the identity, the same parts as the reference wrote them (so {@link #validate} can tell a
     * canonical spelling from another), and the host's ASCII form.
     */
    record Reading(Iri iri, Identity identity, String written, String asciiHost) {

        String path() {
            return identity.path();
        }

        String canonicalSpelling(String original) {
            StringBuilder spelling = new StringBuilder();
            iri.scheme().ifPresent(scheme -> spelling.append(scheme).append(':'));
            if (iri.authority().isPresent()) {
                spelling.append("//");
            }
            spelling.append(identity.text());
            iri.query().ifPresent(query -> spelling.append('?').append(query));
            return spelling.toString();
        }
    }

    /** {@link #read}, for the methods that report a schema's references as a schema's own error. */
    private static Reading reading(String reference) {
        try {
            return read(reference, reference);
        } catch (IdentitySyntaxException e) {
            throw new SchemaValidationException(e.getMessage());
        }
    }

    /**
     * The one reading of a reference: {@code reference} split, judged and put into canonical form. {@code quoted} is
     * the text a refusal names, which is the reference unless a caller derived it from other text.
     *
     * @throws IdentitySyntaxException if {@code reference} names no identity
     */
    static Reading read(String reference, String quoted) {
        Iri iri;
        try {
            iri = Iri.parse(reference, Iri.Grammar.IRI);
        } catch (IriSyntaxException e) {
            throw new IdentitySyntaxException("is not a valid IRI-reference (RFC 3987): it " + e.reason(), quoted);
        }
        if (iri.fragment().isPresent()) {
            throw new IdentitySyntaxException("carries a fragment, not permitted in an identifying URI", quoted);
        }
        Iri.Authority authority = iri.authority().orElse(null);
        String writtenHost = authority == null ? "" : writtenHost(authority.host());
        if (writtenHost.isEmpty() && !iri.path().startsWith("/")) {
            throw new IdentitySyntaxException("has no host and a path that is not absolute: an identity without a "
                    + "host names a library entry by an absolute path, so that it can never be one a host and path "
                    + "also spell", quoted);
        }
        Host host = null;
        String asciiHost = "";
        if (authority != null) {
            if (authority.userinfo().isPresent()) {
                throw new IdentitySyntaxException("carries userinfo, not permitted in an identifying URI", quoted);
            }
            if (authority.port().isPresent()) {
                throw new IdentitySyntaxException("carries a port, not permitted in an identifying URI", quoted);
            }
            if (!writtenHost.isEmpty()) {
                host = host(quoted, authority.host());
                asciiHost = host instanceof HostName name ? name.ascii() : identityText(host);
            }
        }
        Identity identity = new Identity(Optional.ofNullable(host), path(quoted, iri.path()));
        return new Reading(iri, identity, writtenHost + iri.path(), asciiHost);
    }

    /** The host as the reference wrote it, an IPv6 literal in its brackets as the IRI grammar has them. */
    private static String writtenHost(Iri.Host host) {
        return host.kind() == Iri.Host.Kind.IPV6 || host.kind() == Iri.Host.Kind.IP_FUTURE
                ? "[" + host.text() + "]" : host.text();
    }

    private static Host host(String reference, Iri.Host written) {
        if (written.kind() == Iri.Host.Kind.IP_FUTURE) {
            throw new IdentitySyntaxException("has an IPvFuture host, which no identity can hold: an identity's "
                    + "host is a host name or an IPv4 or IPv6 address", reference);
        }
        try {
            return Host.parse(written.kind() == Iri.Host.Kind.IPV6 ? "[" + written.text() + "]" : written.text());
        } catch (HostSyntaxException e) {
            throw new IdentitySyntaxException("has the host '" + e.text() + "', which " + e.reason(), reference);
        }
    }

    /** The host's canonical text as an identity spells it: an IPv6 address in the brackets the IRI grammar needs. */
    static String identityText(Host host) {
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
                throw new IdentitySyntaxException("contains a dot-segment in its path", reference);
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
                    throw new IdentitySyntaxException(
                            "percent-encodes the unreserved character '" + (char) octet + "'", reference);
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
                throw new IdentitySyntaxException("percent-encodes '" + written.substring(i, end) + "', which is "
                        + "not the UTF-8 of any character, so the reference has no IRI form", reference);
            }
            decoded.codePoints().forEach(cp -> {
                if (!Iri.isUcschar(cp) || isBidiFormatting(cp)) {
                    throw new IdentitySyntaxException(("percent-encodes U+%04X, which an IRI cannot hold as itself, "
                            + "so the reference has no IRI form").formatted(cp), reference);
                }
            });
            path.append(decoded);
            i = end;
        }
        String result = path.toString();
        if (!Nfc.of(result).equals(result)) {
            throw new IdentitySyntaxException("has a path not in Normalization Form C -- the path is '"
                    + Nfc.of(result) + "'", reference);
        }
        return result;
    }

    /** The octet a {@code %XX} triplet at {@code at} encodes, hex digits in either case. */
    private static int octet(String reference, String text, int at) {
        if (at + 2 >= text.length()) {
            throw new IdentitySyntaxException("has a malformed percent-encoding", reference);
        }
        int high = Character.digit(text.charAt(at + 1), 16);
        int low = Character.digit(text.charAt(at + 2), 16);
        if (high < 0 || low < 0) {
            throw new IdentitySyntaxException("has a malformed percent-encoding", reference);
        }
        return (high << 4) | low;
    }

    /** RFC 3987 §4.1's bidi formatting characters, which an IRI MUST NOT hold. */
    private static boolean isBidiFormatting(int cp) {
        return cp == 0x200E || cp == 0x200F || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)
                || cp == 0x061C;
    }

    static void percentEncodeBeyondAscii(StringBuilder out, String path) {
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
