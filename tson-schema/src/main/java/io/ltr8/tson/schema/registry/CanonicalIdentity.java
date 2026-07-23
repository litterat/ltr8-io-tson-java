package io.ltr8.tson.schema.registry;

import io.ltr8.tson.schema.SchemaValidationException;

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
 * {@link #of(String)} therefore only ever performs the two reductions the spec actually names;
 * every other check is a rejection, never a rewrite.
 *
 * <p><b>Not part of the public API</b> -- {@code io.ltr8.tson.schema.registry} is this module's
 * internal-by-convention package (`SchemaRegistry`/`SchemaLoader`/`SchemaValidationException` are
 * the user-facing surface, in `io.ltr8.tson.schema` proper). This class is `public` only because
 * `tson-schema` has no `module-info.java` yet, so there's no compiler-enforced way to hide it from
 * a cross-package caller (`SchemaRegistry`) without also hiding it from `SchemaRegistry` itself --
 * a caller outside this module should go through {@code SchemaRegistry} instead of depending on this
 * class directly.
 */
public final class CanonicalIdentity {

    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private CanonicalIdentity() {
    }

    public static String of(String uriString) {
        URI uri;
        try {
            uri = new URI(uriString);
        } catch (URISyntaxException e) {
            throw new SchemaValidationException("'" + uriString + "' is not a valid URI: " + e.getReason());
        }

        if (uri.getScheme() == null) {
            throw new SchemaValidationException("'" + uriString + "' has no scheme");
        }
        if (uri.getHost() == null) {
            throw new SchemaValidationException("'" + uriString + "' has no host");
        }
        if (uri.getUserInfo() != null) {
            throw new SchemaValidationException(
                    "'" + uriString + "' carries userinfo, not permitted in an identifying URI");
        }
        if (uri.getPort() != -1) {
            throw new SchemaValidationException(
                    "'" + uriString + "' carries a port, not permitted in an identifying URI");
        }
        if (uri.getRawFragment() != null) {
            throw new SchemaValidationException(
                    "'" + uriString + "' carries a fragment, not permitted in an identifying URI");
        }

        String host = uri.getHost();
        if (!host.equals(host.toLowerCase(Locale.ROOT))) {
            throw new SchemaValidationException("'" + uriString + "' has a non-lowercase host '" + host + "'");
        }

        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        for (String segment : rawPath.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new SchemaValidationException("'" + uriString + "' contains a dot-segment in its path");
            }
        }

        requireNoPercentEncodedUnreservedCharacters(uriString, host);
        requireNoPercentEncodedUnreservedCharacters(uriString, rawPath);

        return host + rawPath;
    }

    /** RFC 3986 §2.3's unreserved characters MUST NOT be percent-encoded; anything else may be. */
    private static void requireNoPercentEncodedUnreservedCharacters(String uriString, String component) {
        for (int i = 0; i < component.length(); i++) {
            if (component.charAt(i) != '%') {
                continue;
            }
            if (i + 2 >= component.length()) {
                throw new SchemaValidationException("'" + uriString + "' has a malformed percent-encoding");
            }
            int decoded;
            try {
                decoded = Integer.parseInt(component.substring(i + 1, i + 3), 16);
            } catch (NumberFormatException e) {
                throw new SchemaValidationException("'" + uriString + "' has a malformed percent-encoding");
            }
            if (decoded < 128 && UNRESERVED.indexOf((char) decoded) >= 0) {
                throw new SchemaValidationException(
                        "'" + uriString + "' percent-encodes the unreserved character '" + (char) decoded + "'");
            }
            i += 2;
        }
    }
}
