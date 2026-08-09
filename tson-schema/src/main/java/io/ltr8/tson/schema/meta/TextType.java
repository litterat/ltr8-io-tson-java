package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
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
 * <p>Also an {@link Atom} variant: {@code text => !text_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "text_type")
public record TextType(
        @Field("min_length") Optional<Integer> minLength,
        @Field("max_length") Optional<Integer> maxLength,
        Optional<Integer> length,
        Optional<String> pattern) implements Atom {

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
     * <p><b>{@link #pattern} is deliberately unchecked.</b> Deciding whether one I-Regexp accepts a
     * subset of another's language is regular-language containment, and {@code tson-schema} has no
     * dependency on {@code tson-regex} to decide it with (the same boundary the linker's own
     * pattern-disjointness gap sits behind). A refinement may therefore replace a pattern with an
     * unrelated one and pass -- a known hole, not an oversight, and the natural place an injected
     * containment oracle would plug in.
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
