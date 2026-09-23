package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The meta-kernel's {@code text_type} constructor -- the Unicode code point sequence type every
 * other text-shaped atom composes with. Pure constraint values, no parsing/validation behavior --
 * {@code tson-compiler}'s {@code TextParser} holds one of these and does the actual reading/writing.
 *
 * <p><b>{@code pattern} is the regex's own source text ({@link String}), not a compiled {@link
 * java.util.regex.Pattern}</b> -- kept a pure, hashable/equatable value the same as every other
 * field here, and consistent with the kernel's own modeling: {@code regex_type} composes with
 * {@code text_type} (§5.7), i.e. a {@code regex} value IS-A piece of text, so the natural
 * representation of a pattern constraint is text too, not a pre-compiled host object. {@code
 * TextParser}/{@code UriParser} compile it at validation time instead of storing the compiled
 * form.
 *
 * <p><b>{@code members} is the sparse case</b>, as {@link IntegerType#members} is for integers: the strings
 * admitted, written out. Every member must satisfy the other facets on the same body -- the length facets
 * are checked in {@link #coherenceCheck}, the {@code pattern} is not and cannot be, needing the regex engine
 * this module has no dependency on. {@code tson-compiler} carries that half.
 *
 * <p>Also an {@link Atom} variant: {@code text => !text_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "text_type")
public record TextType(
        @Field("min_length") Optional<Integer> minLength,
        @Field("max_length") Optional<Integer> maxLength,
        Optional<Integer> length,
        Optional<String> pattern,
        Optional<List<String>> members) implements Atom {

    @Record
    public TextType {
        members = members.map(List::copyOf);
    }

    /** The four facets that were this type's whole vocabulary before {@link #members} joined them. */
    public TextType(Optional<Integer> minLength, Optional<Integer> maxLength, Optional<Integer> length,
            Optional<String> pattern) {
        this(minLength, maxLength, length, pattern, Optional.empty());
    }

    /** {@code text => !text_type {}} -- the unconstrained text type. */
    public static final TextType UNCONSTRAINED =
            new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>{@link #length} is an exact length, so it acts as both a floor and a ceiling: it folds into
     * the effective length range the stated {@code min_length}/{@code max_length} are compared
     * against, and a refined {@code length} is itself checked against that range from both sides --
     * which is what rejects re-fixing an exactly-5 text to exactly 7.
     *
     * <p><b>{@link #pattern} is settable once.</b> A refinement may set it where the source left it
     * unset, or restate the source's own verbatim, and may never change it. The rule is what it is
     * because the narrowing question is undecided here: whether one I-Regexp accepts a subset of
     * another's language is regular-language containment, and {@code tson-schema} has no dependency on
     * {@code tson-regex} to decide it with (the same boundary the linker's own pattern-disjointness gap
     * sits behind). Refusing the change is the total rule available without that oracle -- an
     * undecidable narrowing is not waved through -- and it is the natural place an injected containment
     * oracle would relax. [TSON-SCHEMA] §5.7's facet-kind table states no rule for {@code pattern}; see
     * {@code SPEC-FEEDBACK.md} #22, which proposes this one.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof TextType other)) {
            return List.of("refines text with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkAtLeast(violations, "min_length", effectiveMinLength(), other.minLength);
        AtomNarrowing.checkAtLeast(violations, "length", effectiveMinLength(), other.length);
        AtomNarrowing.checkAtMost(violations, "max_length", effectiveMaxLength(), other.maxLength);
        AtomNarrowing.checkAtMost(violations, "length", effectiveMaxLength(), other.length);
        AtomNarrowing.checkSettableOnce(violations, "pattern", pattern, other.pattern);
        AtomNarrowing.checkSettableOnce(violations, "members", members, other.members,
                "members and pattern share one position and pattern cannot be narrowed");
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The length family's whole coherence question, shared verbatim by every family that composes
     * these facets ({@link RegexType}, {@link UriType}, {@link EmailType}, which delegate here rather
     * than restating it): the floor may not exceed the ceiling, no count may be negative, and {@link
     * #length} -- pinning both ends at once -- must itself fall inside whatever range the other two
     * leave. {@code { length: 5  max_length: 3 }} is the third case and would otherwise pass, since
     * neither stated facet contradicts the other <em>as a pair</em>.
     *
     * <p><b>{@link #pattern} emptiness is deliberately not checked, and this is a decision rather than a
     * gap.</b> A pattern matching no string would leave the type uninhabited, and the check is cheap to
     * write -- {@code tson-regex}'s disjointness asked of a pattern against itself decides it exactly, from
     * {@code tson-compiler}, which has the engine this module does not. It was built and removed, because
     * <b>an empty language is not reachable by mistake in RFC 9485</b>: I-Regexp has no lookaround, no
     * anchors and no character-class subtraction, and the two errors an author actually makes -- an inverted
     * range ({@code [z-a]}) and a backwards quantifier ({@code a{2,1}}) -- are <em>syntax</em> errors the
     * parser already reports, earlier and better. The one construction that works is a negated class holding
     * a property and its own complement ({@code [^\p{L}\P{L}]}), which nobody writes by accident. The check
     * would have run a product-NFA emptiness computation over every pattern facet at every schema load to
     * catch a deliberate act.
     *
     * <p>Whether a pattern admits no string of a length the same body permits ({@code min_length: 5} beside
     * {@code pattern: "a"}) is a separate question and equally unchecked, needing length-bounded emptiness
     * the engine does not expose.
     *
     * <p><b>{@link #members} against {@link #pattern} is checked, and not here.</b> Every member must match a
     * pattern present on the same body, which is the one member coherence rule in the series needing a regex
     * match rather than a comparison -- so it sits where the engine is, in {@code tson-compiler}'s resolver,
     * beside the call that runs this method. The length facets are checked here because they are counts.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkNonNegative(violations, "min_length", minLength);
        AtomCoherence.checkNonNegative(violations, "max_length", maxLength);
        AtomCoherence.checkNonNegative(violations, "length", length);
        AtomCoherence.checkOrdered(violations, "min_length", minLength, "max_length", maxLength);
        AtomCoherence.checkOrdered(violations, "min_length", minLength, "length", length);
        AtomCoherence.checkOrdered(violations, "length", length, "max_length", maxLength);
        members.ifPresent(set -> set.forEach(member -> {
            int codePoints = member.codePointCount(0, member.length());
            length.filter(fixed -> codePoints != fixed).ifPresent(fixed -> violations.add(
                    "member '" + member + "' is " + codePoints + " characters, and length is " + fixed));
            minLength.filter(min -> codePoints < min).ifPresent(min -> violations.add(
                    "member '" + member + "' is " + codePoints + " characters, under min_length " + min));
            maxLength.filter(max -> codePoints > max).ifPresent(max -> violations.add(
                    "member '" + member + "' is " + codePoints + " characters, over max_length " + max));
        }));
        return List.copyOf(violations);
    }

    /** The tightest floor this type's own length facets impose -- {@link #length} pins both ends, so it counts here too. */
    private Optional<Integer> effectiveMinLength() {
        return Stream.of(minLength, length).flatMap(Optional::stream).max(Integer::compareTo);
    }

    /** The {@link #effectiveMinLength} twin. */
    private Optional<Integer> effectiveMaxLength() {
        return Stream.of(maxLength, length).flatMap(Optional::stream).min(Integer::compareTo);
    }
}
