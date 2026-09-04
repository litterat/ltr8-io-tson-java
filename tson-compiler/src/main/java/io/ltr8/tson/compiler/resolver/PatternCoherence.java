package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.regex.TsonRegex;
import io.ltr8.tson.regex.TsonRegexSyntaxException;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.EmailType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.UriType;

import java.util.List;
import java.util.Optional;

/**
 * The half of {@code Atom.coherenceCheck} that needs a regex engine: a {@code pattern} whose language is
 * empty admits no string at all, so the type it constrains is uninhabited and no document can satisfy it.
 *
 * <p><b>In {@code resolver} rather than the root package</b> for the reason {@code ReferenceChain} is:
 * {@code io.ltr8.tson.compiler} is exported, so a helper there would be module API, and this is internal.
 *
 * <p><b>Here rather than on the family.</b> {@code tson-schema} is a value model and depends on nothing but
 * {@code tson-annotation}; deciding a pattern's language needs {@code tson-regex}, which {@code tson-compiler}
 * already has. So the family keeps every check it can answer from its own fields, and the one that needs an
 * engine runs beside it, at the two sites that ask a body whether it is coherent. It is the split {@code
 * IntegerType} and {@code IntegerParser} already make, applied to a check rather than to a reader -- the
 * alternative, threading an oracle into {@code coherenceCheck}, changes that method's signature for the
 * twenty families that do not need one.
 *
 * <p><b>The emptiness test is the engine's own disjointness, asked of the pattern against itself.</b>
 * {@code TsonRegex.isDisjointFrom} decides whether two patterns share any string, exactly, by a symbolic
 * product-NFA emptiness check -- so {@code p.isDisjointFrom(p)} is true precisely when nothing matches
 * {@code p}. No new engine surface, and the answer is exact rather than an approximation.
 *
 * <p><b>It is reachable, which is why it is worth running.</b> RFC 9485 removes the constructs that usually
 * write an empty language -- no lookaround, no anchors, no character-class subtraction -- but a negated class
 * covering the whole code-point space survives: {@code [^\\p{L}\\P{L}]} parses and matches nothing.
 *
 * <p><b>What it does not check</b> is whether a pattern admits no string of a length the same body permits
 * ({@code min_length: 5} beside {@code pattern: "a"}). That needs length-bounded emptiness the engine does
 * not expose, and the two facets are independently coherent, so it is left alone rather than approximated.
 */
public final class PatternCoherence {

    private PatternCoherence() {
    }

    /** The violations {@code body}'s own pattern raises, or empty where it has none or matches something. */
    public static List<String> check(Top body) {
        return patternOf(body).flatMap(PatternCoherence::emptiness).map(List::of).orElseGet(List::of);
    }

    /** The four text-form families that carry one; {@code RegexType}'s is the pattern a value must match. */
    private static Optional<String> patternOf(Top body) {
        if (!(body instanceof Atom)) {
            return Optional.empty();
        }
        return switch (body) {
            case TextType text -> text.pattern();
            case RegexType regex -> regex.pattern();
            case UriType uri -> uri.pattern();
            case EmailType email -> email.pattern();
            default -> Optional.empty();
        };
    }

    /**
     * A pattern that cannot be parsed raises nothing here: the atom's own reader parser rejects it under its
     * own message, and reporting it twice from two layers helps nobody.
     */
    private static Optional<String> emptiness(String pattern) {
        TsonRegex compiled;
        try {
            compiled = TsonRegex.parse(pattern);
        } catch (TsonRegexSyntaxException notARegex) {
            return Optional.empty();
        }
        if (!compiled.isDisjointFrom(compiled)) {
            return Optional.empty();
        }
        return Optional.of("pattern '" + pattern + "' matches no string at all, so nothing can satisfy this "
                + "type -- a negated class covering every code point is the usual way to write one by accident");
    }
}
