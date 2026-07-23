package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.UriType;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses and validates against meta-kernel's {@code uri_type} constructor (§5.5's {@code uri}
 * atom). Holds a {@link UriType} -- the pure constraint values, unchanged by this split -- rather
 * than declaring those fields itself.
 *
 * <p><b>Delegates entirely to {@link java.net.URI}, unlike every other atom type here.</b> Every
 * other JDK-backed atom in this package (UUID, base64, the temporal family) validates its own shape
 * first, specifically because the relevant JDK parser was confirmed empirically to be more lenient
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

    public UriParser(Optional<Integer> minLength, Optional<Integer> maxLength, Optional<String> pattern,
                      Optional<String> scheme) {
        // "specification" (§5.5's atom_specification mixin, citing RFC 3986) is fixed per
        // constructor, not a per-instance parameter -- every UriParser cites the same one.
        this(new UriType(minLength, maxLength, pattern, UriType.UNCONSTRAINED.specification(), scheme));
    }

    @Override
    public URI read(TokenValue token) {
        String text = token.text();
        URI value;
        try {
            value = new URI(text);
        } catch (URISyntaxException e) {
            throw new AtomParseException("'" + text + "' is not a valid URI (§5.5): " + e.getReason());
        }
        validate(value, text);
        return value;
    }

    @Override
    public String write(URI value) {
        return value.toString();
    }

    private void validate(URI value, String text) {
        constraints.minLength().ifPresent(min -> {
            if (text.length() < min) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, less than the minimum " + min);
            }
        });
        constraints.maxLength().ifPresent(max -> {
            if (text.length() > max) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, more than the maximum " + max);
            }
        });
        constraints.pattern().ifPresent(p -> {
            if (!Pattern.compile(p).matcher(text).matches()) {
                throw new AtomValidationException("'" + text + "' does not match the required pattern " + p);
            }
        });
        constraints.scheme().ifPresent(s -> {
            if (!s.equalsIgnoreCase(value.getScheme())) {
                throw new AtomValidationException(
                        "'" + text + "' has scheme '" + value.getScheme() + "', expected '" + s + "'");
            }
        });
    }
}
