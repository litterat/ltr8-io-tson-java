package io.ltr8.tson.atom.parser;

import io.ltr8.net.Iri;
import io.ltr8.net.IriSyntaxException;
import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.schema.meta.IriType;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Parses and validates against the meta-kernel's {@code iri_type} constructor (§5.5's {@code iri} and {@code
 * iri_reference} atoms), RFC 3987.
 *
 * <p><b>The grammar is RFC 3987's own, through {@link Iri}</b>: every character beyond US-ASCII is a {@code
 * ucschar}, or an {@code iprivate} inside the query, and the rest is RFC 3986's grammar. The facets are {@code
 * uri_type}'s, judged by {@link UriParser#checkFacets} on the text as written. RFC 3987 §4's bidirectional-text
 * rules are a SHOULD and are not checked.
 *
 * <p><b>The value is the {@link Iri}</b> -- the text as written, split into its components. {@code java.net.URI} is
 * a binding target: it admits characters beyond US-ASCII except those {@link Character#isSpaceChar} names, and
 * {@code ucschar} includes a handful of those (U+00A0, U+2000-U+200A, U+3000 and their kin), so a bound {@code URI}
 * holds them percent-encoded and its {@link URI#toString()} is otherwise the text read. A value RFC 2396 cannot
 * hold at all is refused at a {@code URI} component as a binding failure, as {@link UriParser}'s is.
 */
public record IriParser(IriType constraints) implements AtomTypeParser<Iri> {

    /** §5.5's built-in annotation name -- {@code !iri}. */
    public static final String TYPENAME = "iri";

    /** §5.5's built-in annotation name -- {@code !iri_reference}. */
    public static final String REFERENCE_TYPENAME = "iri_reference";

    /** {@code iri => !iri_reference ^ { allow_relative: false }} -- §5.5's {@code !iri}, which has a scheme. */
    public static final IriParser UNCONSTRAINED = new IriParser(IriType.IRI);

    /** {@code iri_reference => !iri_type {}} -- §5.5's {@code !iri_reference}, an IRI or a relative reference. */
    public static final IriParser REFERENCE = new IriParser(IriType.REFERENCE);

    /** {@link #REFERENCE} bound to {@code java.net.URI}, the reading a {@code URI} component gets. */
    public static final AtomType<?> REFERENCE_AS_JAVA_URI = REFERENCE.boundTo(URI.class).orElseThrow();

    @Override
    public Iri read(String written) {
        String text = constraints.normalization().apply(written);
        String subject = TextParser.subject(written, text, constraints.normalization());
        Iri value;
        try {
            value = Iri.parse(text, Iri.Grammar.IRI);
        } catch (IriSyntaxException e) {
            throw new AtomParseException(subject + " is not a valid IRI (RFC 3987): it " + e.reason(), "an IRI");
        }
        UriParser.checkFacets(constraints.uriFacets(), value, text, subject, "an RFC 3987 IRI");
        return value;
    }

    @Override
    public String write(Iri value) {
        return value.text();
    }

    /** {@link Iri}, {@code URI}, and the text it was written as -- {@link UriParser#boundTo}'s reasons, unchanged. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        if (target == Iri.class) {
            return Optional.of(this);
        }
        if (target == URI.class) {
            return Optional.of(new JavaUriAtom(this, IriParser::javaUri));
        }
        if (AtomTypeParser.isTextTarget(target)) {
            return asWrittenText();
        }
        return Optional.empty();
    }

    /** {@code value} as a {@code java.net.URI}, its space-like {@code ucschar}s percent-encoded; see the class doc. */
    static URI javaUri(Iri value) {
        try {
            return new URI(encodeSpaces(value.text()));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("java.net.URI cannot hold it (" + e.getReason() + ")", e);
        }
    }

    /** The text with each character {@link Character#isSpaceChar} names, beyond US-ASCII, percent-encoded as UTF-8. */
    private static String encodeSpaces(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
            int c = text.codePointAt(i);
            if (c <= 0x7F || !Character.isSpaceChar(c)) {
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
