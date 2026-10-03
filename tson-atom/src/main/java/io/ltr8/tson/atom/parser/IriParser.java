package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.schema.meta.IriType;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Parses and validates against meta.tn's {@code iri_type} constructor (§5.5's {@code iri} and {@code
 * iri_reference} atoms), RFC 3987.
 *
 * <p><b>An IRI is judged through the URI it maps to</b> (RFC 3987 §3.1): every character beyond US-ASCII
 * must be a {@code ucschar}, or an {@code iprivate} inside the query, and the text with each such character
 * percent-encoded as UTF-8 must be a URI-reference. The ASCII grammar is therefore {@link UriParser}'s,
 * {@link java.net.URI}'s RFC 2396 gap included, and the facets are {@code uri_type}'s, judged by {@link
 * UriParser#checkFacets} on the text as written. RFC 3987 §4's bidirectional-text rules are a SHOULD and are
 * not checked.
 *
 * <p><b>The host value is a {@link URI} holding the text as written, with one exception.</b> {@code
 * java.net.URI} admits characters beyond US-ASCII except those {@link Character#isSpaceChar} names, and
 * {@code ucschar} includes a handful of those (U+00A0, U+2000-U+200A, U+3000 and their kin). They are held
 * percent-encoded, so no valid IRI is refused and the value compares equal however it was spelled; writing
 * it back gives the encoded spelling. Every other IRI's {@link URI#toString()} is the text it was read from.
 */
public record IriParser(IriType constraints) implements AtomTypeParser<URI> {

    /** §5.5's built-in annotation name -- {@code !iri}. */
    public static final String TYPENAME = "iri";

    /** §5.5's built-in annotation name -- {@code !iri_reference}. */
    public static final String REFERENCE_TYPENAME = "iri_reference";

    /** {@code iri => !iri_reference ^ { allow_relative: false }} -- §5.5's {@code !iri}, which has a scheme. */
    public static final IriParser UNCONSTRAINED = new IriParser(IriType.IRI);

    /** {@code iri_reference => !iri_type {}} -- §5.5's {@code !iri_reference}, an IRI or a relative reference. */
    public static final IriParser REFERENCE = new IriParser(IriType.REFERENCE);

    @Override
    public URI read(String written) {
        String text = constraints.normalization().apply(written);
        checkCharacters(text);
        try {
            new URI(encoded(text, false));
        } catch (URISyntaxException e) {
            throw new AtomParseException("'" + text + "' is not a valid IRI (RFC 3987): " + e.getReason(), "an IRI");
        }
        URI value;
        try {
            value = new URI(encoded(text, true));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("'" + text + "' maps to a URI and still cannot be held as one", e);
        }
        UriParser.checkFacets(constraints.uriFacets(), value, text, "an RFC 3987 IRI");
        return value;
    }

    @Override
    public String write(URI value) {
        return value.toString();
    }

    /** {@code URI}, and the text it was written as -- {@link UriParser#boundTo}'s reasons, unchanged. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        if (target == URI.class) {
            return Optional.of(this);
        }
        if (AtomTypeParser.isTextTarget(target)) {
            return asWrittenText();
        }
        return Optional.empty();
    }

    /** Every character beyond US-ASCII is a {@code ucschar}, or an {@code iprivate} inside the query (§2.2). */
    private static void checkCharacters(String text) {
        int fragment = text.indexOf('#');
        int query = text.indexOf('?');
        if (fragment >= 0 && query > fragment) {
            query = -1;
        }
        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
            int c = text.codePointAt(i);
            if (c <= 0x7F || isUcschar(c)) {
                continue;
            }
            boolean inQuery = query >= 0 && i > query && (fragment < 0 || i < fragment);
            if (isIprivate(c) && inQuery) {
                continue;
            }
            throw new AtomParseException("'" + text + "' has U+" + String.format("%04X", c) + " at index " + i
                    + (isIprivate(c) ? ", a private-use character RFC 3987 admits only in the query"
                            : ", which RFC 3987 admits nowhere in an IRI"), "an IRI");
        }
    }

    /** RFC 3987 §2.2's {@code ucschar}: the BMP's letters and marks, then each supplementary plane but the last two. */
    private static boolean isUcschar(int c) {
        if (c >= 0xA0 && c <= 0xD7FF || c >= 0xF900 && c <= 0xFDCF || c >= 0xFDF0 && c <= 0xFFEF) {
            return true;
        }
        int plane = c >>> 16;
        return plane >= 1 && plane <= 14 && (c & 0xFFFF) <= 0xFFFD && (plane != 14 || c >= 0xE1000);
    }

    /** RFC 3987 §2.2's {@code iprivate}: the private-use area and planes 15 and 16. */
    private static boolean isIprivate(int c) {
        return c >= 0xE000 && c <= 0xF8FF || c >= 0xF0000 && (c & 0xFFFF) <= 0xFFFD;
    }

    /**
     * The text with characters beyond US-ASCII percent-encoded as UTF-8: all of them, which is RFC 3987
     * §3.1's mapping to a URI, or only those {@link java.net.URI} cannot hold.
     */
    private static String encoded(String text, boolean onlySpaces) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
            int c = text.codePointAt(i);
            if (c <= 0x7F || onlySpaces && !Character.isSpaceChar(c)) {
                out.appendCodePoint(c);
                continue;
            }
            for (byte b : new String(Character.toChars(c)).getBytes(StandardCharsets.UTF_8)) {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }
}
