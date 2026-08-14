package io.ltr8.tson.schema;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The canonical-identity algorithm a {@code !!id}/{@code !!import} URI is compared and registered
 * under ({@code [TSON-DATA] §2.2.1}): "a documented profile of RFC 3986 §6.2.1 (simple string
 * comparison), reached by two reductions: (1) remove the scheme and its {@code ://} delimiter, and
 * (2) remove the query component. What remains — lowercase host plus path — is the identity."
 *
 * <p><b>This is not general URI normalization.</b> The spec is explicit that everything other than
 * the scheme and query MUST already be in canonical form -- lowercase host, no userinfo, no port
 * (default or otherwise), no percent-encoding of unreserved characters, no dot-segments, and no
 * fragment -- and that an identifier failing any of these is an *error*, not something to fix up:
 * "no case folding, path resolution, or percent-decoding is ever performed at comparison time."
 * {@link #canonicalize(String)} therefore only ever performs the two reductions the spec actually
 * names; every other check is a rejection, never a rewrite.
 *
 * <p><b>Public, and part of the contract of every identity-bearing seam.</b> {@link
 * TsonSchemaLoader#load} takes a canonical identity as its argument, and a {@code TsonSchemaSource}
 * is asked for a document by one, so anything implementing either has to derive and compare
 * identities exactly the way the library does -- which is this class. The half of §2.2.1 that reads
 * the {@code ?sha256=} pin this one strips lives in {@code TsonContentHash}.
 *
 * <p>The methods return and compare plain {@code String}s rather than instances of this type: a
 * canonical identity is a map key throughout the registries, and wrapping it would buy type-safety
 * only if every identity-carrying signature were converted at once.
 */
public final class TsonCanonicalIdentity {

    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private TsonCanonicalIdentity() {
    }

    /**
     * The canonical identity of {@code uriString} -- scheme and query stripped, the rest required already
     * canonical. This is the identity references are matched by, so a {@code ?sha256=} pin (verification
     * metadata, not identity) doesn't distinguish a pinned reference from a plain one, and {@code http://}
     * and {@code https://} spellings name the same thing.
     *
     * @throws TsonSchemaValidationException if {@code uriString} isn't a valid canonical-identity candidate
     */
    public static String canonicalize(String uriString) {
        URI uri;
        try {
            uri = new URI(uriString);
        } catch (URISyntaxException e) {
            throw new TsonSchemaValidationException("'" + uriString + "' is not a valid URI: " + e.getReason());
        }

        if (uri.getScheme() == null) {
            throw new TsonSchemaValidationException("'" + uriString + "' has no scheme");
        }
        if (uri.getHost() == null) {
            throw new TsonSchemaValidationException("'" + uriString + "' has no host");
        }
        if (uri.getUserInfo() != null) {
            throw new TsonSchemaValidationException(
                    "'" + uriString + "' carries userinfo, not permitted in an identifying URI");
        }
        if (uri.getPort() != -1) {
            throw new TsonSchemaValidationException(
                    "'" + uriString + "' carries a port, not permitted in an identifying URI");
        }
        if (uri.getRawFragment() != null) {
            throw new TsonSchemaValidationException(
                    "'" + uriString + "' carries a fragment, not permitted in an identifying URI");
        }

        String host = uri.getHost();
        if (!host.equals(host.toLowerCase(Locale.ROOT))) {
            throw new TsonSchemaValidationException("'" + uriString + "' has a non-lowercase host '" + host + "'");
        }

        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new TsonSchemaValidationException("'" + uriString + "' contains a dot-segment in its path");
            }
        }

        requireNoPercentEncodedUnreservedCharacters(uriString, host);
        requireNoPercentEncodedUnreservedCharacters(uriString, rawPath);

        return host + rawPath;
    }

    /**
     * Runs {@link #canonicalize}'s checks and discards the identity -- for a caller validating a candidate
     * {@code !!id} up front (before resolving a whole document that will eventually need one) rather than
     * looking anything up. Exists so that intent reads at the call site, where computing an identity only to
     * throw it away would not.
     *
     * @throws TsonSchemaValidationException if {@code uriString} isn't a valid canonical-identity candidate
     */
    public static void validate(String uriString) {
        canonicalize(uriString);
    }

    /**
     * Whether two URIs name one identity -- {@link #canonicalize} applied to both, then compared. The
     * spelling-insensitive comparison §2.2.1 calls for: scheme and {@code ?sha256=} pin differences don't
     * make two references distinct.
     *
     * @throws TsonSchemaValidationException if either argument isn't a valid canonical-identity candidate
     */
    public static boolean sameIdentity(String uriString, String otherUriString) {
        return canonicalize(uriString).equals(canonicalize(otherUriString));
    }

    /** RFC 3986 §2.3's unreserved characters MUST NOT be percent-encoded; anything else may be. */
    private static void requireNoPercentEncodedUnreservedCharacters(String uriString, String component) {
        for (int i = 0; i < component.length(); i++) {
            if (component.charAt(i) != '%') {
                continue;
            }
            if (i + 2 >= component.length()) {
                throw new TsonSchemaValidationException("'" + uriString + "' has a malformed percent-encoding");
            }
            int decoded;
            try {
                decoded = Integer.parseInt(component.substring(i + 1, i + 3), 16);
            } catch (NumberFormatException e) {
                throw new TsonSchemaValidationException("'" + uriString + "' has a malformed percent-encoding");
            }
            if (decoded < 128 && UNRESERVED.indexOf((char) decoded) >= 0) {
                throw new TsonSchemaValidationException(
                        "'" + uriString + "' percent-encodes the unreserved character '" + (char) decoded + "'");
            }
            i += 2;
        }
    }
}
