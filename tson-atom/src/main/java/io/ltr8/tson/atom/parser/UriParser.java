package io.ltr8.tson.atom.parser;

import io.ltr8.net.Iri;
import io.ltr8.net.IriSyntaxException;
import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.base.unicode.Normalization;
import io.ltr8.tson.regex.TsonRegex;
import io.ltr8.tson.schema.meta.UriType;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

/**
 * Parses and validates against meta.tn's {@code uri_type} constructor (§5.5's {@code uri} and
 * {@code uri_reference} atoms). Holds a {@link UriType} -- the pure constraint values -- rather than
 * declaring those fields itself.
 *
 * <p>A URI is US-ASCII (RFC 3986 §2); text with a character beyond it is refused here and read by {@link
 * IriParser}, RFC 3987's family.
 *
 * <p><b>The grammar is RFC 3986's own, through {@link Iri}</b>, and the value is the {@link Iri} it reads to: the
 * text as written, split into its components. {@code java.net.URI} implements RFC 2396 instead, which refuses
 * {@code https://} and {@code a:} and admits {@code http://a:b/}, so it is a binding target here and never the
 * judge: a component declared {@code URI} binds every value it can hold, and a value it cannot ({@code https://}) is
 * refused at that component as a binding failure, its validity unchanged.
 */
public record UriParser(UriType constraints) implements AtomTypeParser<Iri> {

    /** §5.5's built-in annotation name -- {@code !uri}. */
    public static final String TYPENAME = "uri";

    /** §5.5's built-in annotation name -- {@code !uri_reference}. */
    public static final String REFERENCE_TYPENAME = "uri_reference";

    /** {@code uri => !uri_reference ^ { allow_relative: false }} -- §5.5's {@code !uri}, which has a scheme. */
    public static final UriParser UNCONSTRAINED = new UriParser(UriType.URI);

    /** {@code uri_reference => !uri_type {}} -- §5.5's {@code !uri_reference}, a URI or a relative reference. */
    public static final UriParser REFERENCE = new UriParser(UriType.REFERENCE);

    /**
     * Every facet {@code uri_type} carries except {@code spec}, which §5.5 fixes per constructor --
     * every {@link UriParser} cites RFC 3986.
     */
    public UriParser(Optional<Integer> minLength, Optional<Integer> maxLength, Optional<Integer> length,
                      Optional<String> pattern, Optional<List<String>> schemes, boolean allowRelative,
                      boolean allowFragment) {
        this(new UriType(UriType.SPEC, minLength, maxLength, length, pattern,
                Optional.empty(), schemes, allowRelative, allowFragment, Normalization.NONE));
    }

    @Override
    public Iri read(String written) {
        String text = constraints.normalization().apply(written);
        String subject = TextParser.subject(written, text, constraints.normalization());
        // A character beyond US-ASCII makes the text an IRI (RFC 3987), which is iri_type's family; said first,
        // so the refusal points at the other family rather than at the URI grammar.
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                throw new AtomParseException(subject + " has U+" + String.format("%04X", text.codePointAt(i))
                        + " at index " + i + ", beyond the US-ASCII of a URI (RFC 3986 §2); an IRI is written !iri",
                        "a URI");
            }
        }
        Iri value;
        try {
            value = Iri.parse(text, Iri.Grammar.URI);
        } catch (IriSyntaxException e) {
            throw new AtomParseException(subject + " is not a valid URI (RFC 3986): it " + e.reason(), "a URI");
        }
        checkFacets(constraints, value, text, subject, "an RFC 3986 URI");
        return value;
    }

    @Override
    public String write(Iri value) {
        return value.text();
    }

    /**
     * {@link Iri}, {@code java.net.URI}, and the text it was written as. A {@code uri}'s wire form is text and an
     * {@link Iri} is that text, so a component holding the validated spelling loses nothing -- which is why §5.5's
     * own facets (length, pattern) are measured on the text. The value is read first: a target chooses the
     * representation, never the rules.
     *
     * <p><b>{@link java.net.URL} is deliberately absent.</b> It is not a narrowing: {@code URI.toURL()} is
     * partial over this family's value space -- a {@code urn:}, a relative reference and a bare fragment are
     * all valid here and none of them is a URL -- and which of the rest convert depends on the protocol
     * handlers a JVM happens to have, so one document would bind on one deployment and not another. Its
     * {@code equals} resolves host names as well, which would put blocking I/O inside the {@code equals} of
     * whatever record held one.
     */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        if (target == Iri.class) {
            return Optional.of(this);
        }
        if (target == URI.class) {
            return Optional.of(new JavaUriAtom(this, UriParser::javaUri));
        }
        if (AtomTypeParser.isTextTarget(target)) {
            return asWrittenText();
        }
        return Optional.empty();
    }

    /**
     * {@code value} as a {@code java.net.URI} holding its text, or an {@link IllegalArgumentException} -- the
     * binding failure a target too narrow for the value raises -- where RFC 2396 cannot hold what RFC 3986 admits.
     */
    static URI javaUri(Iri value) {
        try {
            return new URI(value.text());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("java.net.URI cannot hold it (" + e.getReason() + ")", e);
        }
    }

    /**
     * {@code uri_type}'s facets judged against one parsed value -- shared with {@link IriParser}, whose facets
     * are the same and mean the same. {@code absolute} names the form a withdrawn {@code allow_relative}
     * requires, for {@code expected}. Length and pattern are measured on {@code text}, the value's text in the
     * type's form, and a refusal names it as {@code subject} ({@link TextParser#subject}).
     */
    static void checkFacets(UriType constraints, Iri value, String text, String subject, String absolute) {
        if (!constraints.allowRelative() && value.isRelative()) {
            throw new AtomValidationException(
                    subject + " is a relative reference, and the type requires a scheme", absolute);
        }
        TextParser.checkLengths(text, subject, constraints.length(), constraints.minLength(),
                constraints.maxLength());
        // Pattern is I-Regexp (RFC 9485), matched via tson-regex (linear-time, ReDoS-safe), not
        // java.util.regex; already validated well-formed at schema resolution (see RegexParser).
        constraints.pattern().ifPresent(p -> {
            if (!TsonRegex.parse(p).matches(text)) {
                throw new AtomValidationException(subject + " does not match the required pattern " + p,
                        "matching " + p);
            }
        });
        String scheme = value.scheme().orElse(null);
        if (!constraints.admitsScheme(scheme)) {
            String admitted = "scheme one of (" + String.join(", ", constraints.schemes().orElseThrow()) + ")";
            throw new AtomValidationException(scheme == null
                    ? subject + " has no scheme, and the type admits " + admitted
                    : subject + " has scheme '" + scheme + "', and the type admits " + admitted,
                    admitted);
        }
        if (!constraints.allowFragment() && value.fragment().isPresent()) {
            throw new AtomValidationException(subject + " has a fragment, which the type refuses", "no fragment");
        }
    }
}
