package io.ltr8.tson.base;

import io.ltr8.net.Host;

import java.util.Objects;
import java.util.Optional;

/**
 * A canonical identity ([TSON-DATA] §2.2.1) as a value: core's {@code identity} atom. A host and the path after
 * it, or an absolute path alone; two are equal exactly when their {@link #text()}s are, so the {@code http://} and
 * {@code https://} spellings of a reference, a pinned and a plain one, {@code Example.COM} and {@code example.com},
 * {@code xn--bcher-kva.example} and {@code bücher.example}, {@code sch%c3%a9mas} and {@code schémas} each name one
 * value. A hosted identity never equals a path-only one.
 *
 * <p><b>Two readings, one rule underneath.</b> {@link #ofReference} reads a reference in any spelling
 * {@link CanonicalIdentity#canonicalize} accepts, and is the reading that method and its siblings use.
 * {@link #parse} is the atom's lexical space: a reference, or the canonical identity's own text -- no scheme, and
 * beginning with a host ({@code tson.io/2026/38/m/core.tn}, {@code [::1]/x.tn}) or with {@code /}. A text
 * beginning with {@code /} or with a scheme is a reference; any other is read as the identity of its
 * {@code https://} spelling. The two kinds are disjoint by their first character because a path-only identity is
 * absolute. {@link #parse} also refuses a query holding anything but a hash parameter, as the loader does
 * ({@link CanonicalIdentity#declaredSha256}).
 *
 * @param host the host, as a {@link Host} value; empty for a path-only identity
 * @param path the path, NFC, with each percent-encoding of a character beyond US-ASCII decoded and every other one in
 *             uppercase hex; absolute when there is no host, and empty or beginning with {@code /} after one
 */
public record Identity(Optional<Host> host, String path) {

    /** A path-only identity's path is absolute, and a hosted one's path is empty or begins with {@code /}. */
    public Identity {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(path, "path");
        if (host.isEmpty() ? !path.startsWith("/") : !(path.isEmpty() || path.startsWith("/"))) {
            throw new IllegalArgumentException(host.isEmpty()
                    ? "a path-only identity's path is absolute: '" + path + "'"
                    : "a hosted identity's path is empty or begins with '/': '" + path + "'");
        }
    }

    /**
     * The value {@code text} names: a reference in any spelling of its identity, or the canonical identity's text.
     *
     * @throws IdentitySyntaxException if {@code text} names no identity, or carries a query that is not hash
     *     parameters
     */
    public static Identity parse(String text) {
        boolean reference = text.startsWith("/") || hasScheme(text);
        Identity identity = CanonicalIdentity.read(reference ? text : "https://" + text, text).identity();
        CanonicalIdentity.declaredSha256(text);
        return identity;
    }

    /**
     * The identity {@code reference} names, scheme and query removed and the query unread -- the reading
     * {@link CanonicalIdentity#canonicalize} applies.
     *
     * @throws IdentitySyntaxException if {@code reference} names no identity
     */
    public static Identity ofReference(String reference) {
        return CanonicalIdentity.read(reference, reference).identity();
    }

    /**
     * The canonical identity: the host's canonical text, an IPv6 address in brackets, then the path --
     * {@code tson.io/2026/38/m/core.tn}, {@code [::1]/x.tn}, {@code /local/orders.tn}. The string
     * {@link CanonicalIdentity#canonicalize} returns.
     */
    public String text() {
        return host.map(CanonicalIdentity::identityText).orElse("") + path;
    }

    /**
     * The URI spelling (RFC 3987 §3.1): for a hosted identity its {@code https://} reference, the host by ToASCII;
     * for a path-only one the path. Each character of the path beyond US-ASCII is its percent-encoded UTF-8.
     */
    public String toUri() {
        return CanonicalIdentity.toUri(host.isPresent() ? "https://" + text() : path);
    }

    /** The canonical identity, as {@link #text()}. */
    @Override
    public String toString() {
        return text();
    }

    /** RFC 3986 §3.1's {@code scheme ":"} at the start of {@code text}. */
    private static boolean hasScheme(String text) {
        int colon = text.indexOf(':');
        if (colon < 1 || !isAsciiLetter(text.charAt(0))) {
            return false;
        }
        for (int i = 1; i < colon; i++) {
            char c = text.charAt(i);
            if (!(isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
