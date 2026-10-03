package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.base.unicode.IdentifierProfile;
import io.ltr8.tson.base.unicode.IdentifierProfile.Base;
import io.ltr8.tson.base.unicode.Normalization;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The meta-kernel's {@code identifier_type} constructor ({@code text_type & atom_specification & { ... }}) -- a
 * UAX #31 identifier profile, with {@code text_type}'s facets applied inside it: lengths, a {@code pattern} such
 * as a naming convention, and {@code members} for a closed vocabulary of names. Pure constraint values;
 * {@code tson-atom}'s {@code IdentifierParser} holds one and does the reading, through the
 * {@link IdentifierProfile} that {@link #profile()} builds.
 *
 * <p>The profile facets are {@code start} and {@code continue} (the Unicode property each set is drawn from),
 * {@code start_add}, {@code continue_add}, {@code medial} and {@code exclude} (each the set of code points its
 * text holds); {@link IdentifierProfile} states how they combine. {@code normalization} is {@code text_type}'s,
 * defaulting to NFC here: the profile judges the value, which is the text put into that form. {@code spec} is
 * pinned to UAX #31.
 *
 * <p>Every field is flat, mirroring the resolved shape rather than the composition that produced it, as
 * {@link UriType}'s are. {@code continue} is a Java keyword, so its component is {@link #continueBase}.
 *
 * <p>{@code identifier => !identifier_type { continue_add: "-" }} is a constructor-application instance (§5.5)
 * whose resolved body is exactly {@link #IDENTIFIER}, and whose profile is {@link IdentifierProfile#NAME}.
 */
@Typename(name = "identifier_type")
public record IdentifierType(
        String spec,
        @Field("min_length") Optional<Integer> minLength,
        @Field("max_length") Optional<Integer> maxLength,
        Optional<Integer> length,
        Optional<String> pattern,
        Optional<List<String>> members,
        Base start,
        @Field("continue") Base continueBase,
        @Field("start_add") Optional<String> startAdd,
        @Field("continue_add") Optional<String> continueAdd,
        Optional<String> medial,
        Optional<String> exclude,
        Normalization normalization) implements Atom, TextFamily {

    @Record
    public IdentifierType {
        members = members.map(List::copyOf);
    }

    /** {@code identifier_type.spec}'s pinned value. */
    public static final String SPEC = "https://www.unicode.org/reports/tr31/";

    /** {@code identifier => !identifier_type { continue_add: "-" }} -- [TSON-DATA] §7.7's identifier. */
    public static final IdentifierType IDENTIFIER = new IdentifierType(SPEC, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Base.XID, Base.XID, Optional.empty(),
            Optional.of("-"), Optional.empty(), Optional.empty(), Normalization.NFC);

    /** The {@code text_type} facets this composes, as the {@link TextType} that owns their comparison rules. */
    public TextType textConstraints() {
        return new TextType(minLength, maxLength, length, pattern, members, normalization);
    }

    /** This profile with {@code text}'s facets in place of its own. */
    public IdentifierType withTextConstraints(TextType text) {
        return new IdentifierType(spec, text.minLength(), text.maxLength(), text.length(), text.pattern(),
                text.members(), start, continueBase, startAdd, continueAdd, medial, exclude,
                text.normalization());
    }

    /** The profile the profile facets make. Built on each call: a reader holds the one it built. */
    public IdentifierProfile profile() {
        return IdentifierProfile.of(start, continueBase, startAdd.orElse(""), continueAdd.orElse(""),
                medial.orElse(""), exclude.orElse(""), normalization);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The text facets narrow as {@link TextType}'s own rule says, {@code normalization} among them. <b>The
     * profile facets do not move at all</b>: a refinement restates each one or leaves it. Narrowing a profile
     * has no use a fresh {@code !identifier_type} does not serve better, and an addition set cannot follow the
     * other facets' set-once rule, because setting {@code start_add} on a source that left it unset widens the profile --
     * the one direction a refinement never goes. The sets compare as sets, however they are spelled.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof IdentifierType other)) {
            return List.of("refines an identifier with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>(textConstraints().constraintsCheck(other.textConstraints()));
        fixed(violations, "start", start, other.start);
        fixed(violations, "continue", continueBase, other.continueBase);
        fixedSet(violations, "start_add", startAdd, other.startAdd);
        fixedSet(violations, "continue_add", continueAdd, other.continueAdd);
        fixedSet(violations, "medial", medial, other.medial);
        fixedSet(violations, "exclude", exclude, other.exclude);
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The facets this composes, judged by {@link TextType#coherenceCheck} that owns them; the profile's own
     * ({@link IdentifierProfile#incoherence}); and one rule joining them: every member, as the value it is in
     * {@code normalization}'s form, is an identifier under this profile. A member the profile refuses is one no
     * value can ever be, since a value is refused by the profile before a facet is asked, so it is refused here
     * with the rest.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>(textConstraints().coherenceCheck());
        IdentifierProfile profile = profile();
        violations.addAll(profile.incoherence());
        members.ifPresent(set -> set.forEach(member -> profile.check(normalization.apply(member)).ifPresent(why ->
                violations.add("member '" + member + "' is not an identifier: " + why))));
        return List.copyOf(violations);
    }

    private static void fixed(List<String> violations, String facet, Object source, Object refined) {
        if (!Objects.equals(source, refined)) {
            violations.add(changed(facet, source, refined));
        }
    }

    private static void fixedSet(List<String> violations, String facet, Optional<String> source,
                                 Optional<String> refined) {
        String from = source.orElse("");
        String to = refined.orElse("");
        if (!from.codePoints().sorted().distinct().boxed().toList()
                .equals(to.codePoints().sorted().distinct().boxed().toList())) {
            violations.add(changed(facet, "\"" + from + "\"", "\"" + to + "\""));
        }
    }

    private static String changed(String facet, Object source, Object refined) {
        return "changes '" + facet + "' from " + source + " to " + refined
                + " -- an identifier profile is fixed where it is constructed, and a refinement only restates it";
    }
}
