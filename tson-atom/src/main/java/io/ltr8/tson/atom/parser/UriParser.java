package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.regex.TsonRegex;
import io.ltr8.tson.schema.meta.UriType;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Parses and validates against meta-kernel's {@code uri_type} constructor (§5.5's {@code uri}
 * atom). Holds a {@link UriType} -- the pure constraint values, unchanged by this split -- rather
 * than declaring those fields itself.
 *
 * <p><b>Delegates entirely to {@link java.net.URI}, unlike every other atom type here.</b> Every
 * other JDK-backed atom in this package (UUID, base64, the temporal family) validates its own shape
 * first, specifically because the relevant JDK compiler was confirmed empirically to be more lenient
 * than the RFC the spec cites. {@code java.net.URI} is a different situation entirely: its own
 * Javadoc states it implements RFC 2396 (as amended by RFC 2732), not RFC 3986, which §5.5 cites --
 * an *older revision* of the same standard, not a looser/stricter variant of the *same* grammar the
 * way the other JDK leniencies were. Reconciling the two would mean writing an RFC 3986 validator
 * from scratch (§5.5 has no simpler shape to shim in front of {@code URI}'s constructor the way a
 * four-group hex pattern works for UUID), which isn't worth it at this stage -- {@code
 * java.net.URI}'s behavior is accepted as this atom's actual contract for now. See {@code
 * README.md}'s Conformance section for the one-line version of this note.
 */
public record UriParser(UriType constraints) implements AtomType<URI> {

    /** §5.5's built-in annotation name -- {@code !uri}. */
    public static final String TYPENAME = "uri";

    /** {@code uri => !uri_type {}} -- the unconstrained URI, §5.5's {@code !uri}. */
    public static final UriParser UNCONSTRAINED = new UriParser(UriType.UNCONSTRAINED);

    /**
     * Every facet {@code uri_type} carries except {@code spec}, which §5.5 fixes per constructor --
     * every {@link UriParser} cites RFC 3986.
     */
    public UriParser(Optional<Integer> minLength, Optional<Integer> maxLength, Optional<Integer> length,
                      Optional<String> pattern, Optional<String> scheme) {
        this(new UriType(UriType.UNCONSTRAINED.spec(), minLength, maxLength, length, pattern, scheme));
    }

    @Override
    public URI read(String text) {
        URI value;
        try {
            value = new URI(text);
        } catch (URISyntaxException e) {
            throw new AtomParseException("'" + text + "' is not a valid URI (§5.5): " + e.getReason(), "a URI");
        }
        validate(value, text);
        return value;
    }

    @Override
    public String write(URI value) {
        return value.toString();
    }

    /**
     * {@code URI}, and the text it was written as. A {@code uri}'s wire form is text and
     * {@link URI#toString()} hands back the string it was built from, so a component holding the validated
     * spelling loses nothing -- which is why §5.5's own facets (length, pattern) are measured on the text.
     *
     * <p><b>{@link java.net.URL} is deliberately absent.</b> It is not a narrowing: {@code URI.toURL()} is
     * partial over this family's value space -- a {@code urn:}, a relative reference and a bare fragment are
     * all valid here and none of them is a URL -- and which of the rest convert depends on the protocol
     * handlers a JVM happens to have, so one document would bind on one deployment and not another. Its
     * {@code equals} resolves host names as well, which would put blocking I/O inside the {@code equals} of
     * whatever record held one.
     */
    @Override
    public boolean admits(Class<?> target) {
        return target == URI.class || target == String.class || target == CharSequence.class;
    }

    /** {@inheritDoc} <p>The text where {@link #admits} named a string target, the {@link URI} otherwise. */
    @Override
    public Object read(String text, Class<?> target) {
        URI value = read(text);
        if (target == String.class || target == CharSequence.class) {
            return value.toString();
        }
        if (!AtomType.wrap(target).isInstance(value)) {
            throw new AtomValidationException("cannot represent " + value + " as " + target,
                    "a value representable as " + target.getSimpleName());
        }
        return value;
    }

    private void validate(URI value, String text) {
        constraints.length().ifPresent(len -> {
            if (text.length() != len) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, expected exactly " + len,
                        "exactly " + len + " characters");
            }
        });
        constraints.minLength().ifPresent(min -> {
            if (text.length() < min) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, less than the minimum " + min,
                        "at least " + min + " characters");
            }
        });
        constraints.maxLength().ifPresent(max -> {
            if (text.length() > max) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, more than the maximum " + max,
                        "at most " + max + " characters");
            }
        });
        // Pattern is I-Regexp (RFC 9485), matched via tson-regex (linear-time, ReDoS-safe), not
        // java.util.regex; already validated well-formed at schema resolution (see RegexParser).
        constraints.pattern().ifPresent(p -> {
            if (!TsonRegex.parse(p).matches(text)) {
                throw new AtomValidationException("'" + text + "' does not match the required pattern " + p,
                        "matching " + p);
            }
        });
        constraints.scheme().ifPresent(s -> {
            if (!s.equalsIgnoreCase(value.getScheme())) {
                throw new AtomValidationException(
                        "'" + text + "' has scheme '" + value.getScheme() + "', expected '" + s + "'",
                        "scheme " + s);
            }
        });
    }
}
