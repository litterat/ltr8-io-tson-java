package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.base.unicode.IdentifierProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code identifier_type} constructor ({@code identifier_type => text_type & {}}) -- the
 * identifier grammar of [TSON-DATA] §7.7, with {@code text_type}'s facets applied inside it: lengths, a
 * {@code pattern} such as a naming convention, and {@code members} for a closed vocabulary of names. Pure
 * constraint values; {@code tson-atom}'s {@code IdentifierParser} holds one and does the reading.
 *
 * <p>Every field is flat, mirroring the resolved shape rather than the composition that produced it, as
 * {@link RegexType}'s are; it has no {@code spec}, the grammar being the kernel's own.
 *
 * <p>{@code identifier => !identifier_type {}} is a constructor-application instance (§5.5) whose resolved body
 * is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "identifier_type")
public record IdentifierType(
        @Field("min_length") Optional<Integer> minLength,
        @Field("max_length") Optional<Integer> maxLength,
        Optional<Integer> length,
        Optional<String> pattern,
        Optional<List<String>> members) implements Atom {

    @Record
    public IdentifierType {
        members = members.map(List::copyOf);
    }

    /** {@code identifier => !identifier_type {}} -- the unconstrained identifier type. */
    public static final IdentifierType UNCONSTRAINED = new IdentifierType(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());

    /** The {@code text_type} facets this composes, as the {@link TextType} that owns their comparison rules. */
    public TextType textConstraints() {
        return new TextType(minLength, maxLength, length, pattern, members);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The narrowing rule is {@link TextType}'s own, applied to the facets this composes; the grammar is
     * fixed and cannot move.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof IdentifierType other)) {
            return List.of("refines an identifier with " + refined.getClass().getSimpleName());
        }
        return textConstraints().constraintsCheck(other.textConstraints());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The facets this composes, judged by {@link TextType#coherenceCheck} that owns them, and one rule of its
     * own: every member is an identifier. A member outside §7.7's grammar is one no value can ever be, since a
     * value is refused by the grammar before a facet is asked, so it is refused here with the rest.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>(textConstraints().coherenceCheck());
        members.ifPresent(set -> set.forEach(member -> IdentifierProfile.validate(member).ifPresent(why ->
                violations.add("member '" + member + "' is not an identifier: " + why))));
        return List.copyOf(violations);
    }
}
