package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.RegexType;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Parses and validates against meta-kernel's {@code regex_type} constructor ({@code ~text_type &
 * atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc9485" }}) -- reuses {@link
 * TextParser}'s length/pattern constraint checks (applied to the regex's own source text, not what
 * it matches) via composition rather than duplicating them, and additionally requires that text to
 * actually compile as a regular expression. Holds a {@link RegexType} -- the pure constraint
 * values, unchanged by this split -- rather than declaring those fields itself. Not part of Part
 * 1's published built-in vocabulary (§5) and never registered in {@link BuiltinTypeVocabulary} --
 * this is groundwork for Part 2, which doesn't yet have anything that consumes a {@code regex}
 * value as a constraint.
 *
 * <p><b>Accepts {@code java.util.regex.Pattern}'s own syntax, not a real RFC 9485 validator -- a
 * conformance decision, not a shape-check gap the way the rest of this package's JDK leniency notes
 * are.</b> Every other JDK-backed atom here (UUID, base64, the temporal family) adds a stricter shape
 * check in front of a JDK parser that's *more lenient* than the cited RFC/ISO grammar -- the same
 * grammar, just under-enforced. RFC 9485 (I-Regexp) isn't that: it's a deliberately restricted,
 * interoperable subset -- also used by XML Schema, XPath, and JSON Schema's {@code format: "regex"}
 * -- of a different regex dialect (roughly ECMA-262) than {@code java.util.regex}'s own Perl-derived
 * syntax. Neither is a subset of the other ({@code java.util.regex} accepts constructs I-Regexp
 * doesn't define at all, such as its named-group and lookbehind syntax; I-Regexp requires XML
 * Schema-style character class subtraction {@code java.util.regex} doesn't support). Reconciling the
 * two would mean writing an RFC 9485 validator from scratch -- real, standalone work, not a small
 * shim -- and with nothing in Part 2 actually consuming a {@code regex} constraint yet, it isn't
 * worth doing until something does. {@code java.util.regex.Pattern}'s own compile-time syntax check
 * is accepted as this atom's actual contract for now; see {@code README.md}'s Conformance section for
 * the one-line version of this note.
 */
public record RegexParser(RegexType constraints) implements AtomType<Pattern> {

    /** {@code regex => !regex_type {}} -- the unconstrained regex type. */
    public static final RegexParser UNCONSTRAINED = new RegexParser(RegexType.UNCONSTRAINED);

    @Override
    public Pattern read(TokenValue token) {
        String text = new TextParser(constraints.constraints()).read(token);
        try {
            return Pattern.compile(text);
        } catch (PatternSyntaxException e) {
            throw new AtomParseException("'" + text + "' is not a valid regular expression: " + e.getMessage());
        }
    }

    @Override
    public String write(Pattern value) {
        return value.pattern();
    }
}
