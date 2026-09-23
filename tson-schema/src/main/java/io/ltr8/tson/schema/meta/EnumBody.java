package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.base.unicode.IdentifierProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * The meta-kernel's {@code enum} constructor's own vocabulary, resolved (Part 2 §4.1, §8.1):
 * {@code members: set<text>} and the {@code profile} saying which kind of enumeration they spell --
 * backs {@code boolean} (`[true false]`), the kernel's own internal enumerations ({@code
 * product_access_type}, {@code field_state}, ...), and every user-declared {@code !enum [...]}
 * instance. Kept as an ordered {@code List}, matching how {@link TypeDefinition#supertypes}/{@link
 * TypeDefinition#subtypes} already represent conceptual sets -- member order is preserved for
 * deterministic output, not semantically significant.
 *
 * <p><b>Members are text, and {@link EnumProfile#IDENTIFIER} is a constraint on them rather than their
 * type.</b> So the identifier rule is checked here, in {@link #coherenceCheck}, where a body that declares
 * itself a vocabulary is judged against what it holds -- not by the member set's element type, which admits
 * any text and must, since {@link EnumProfile#TEXT} is the other half of the same constructor.
 */
@Typename(name = "enum")
public record EnumBody(List<String> members, EnumProfile profile) implements Atom {

    @Record
    public EnumBody {
        members = List.copyOf(members);
    }

    /** The default profile, for the callers that build a vocabulary and have nothing else to say. */
    public EnumBody(List<String> members) {
        this(members, EnumProfile.IDENTIFIER);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An enum's value set is written out in full, so narrowing is plain subset containment: a
     * refinement may drop members but never introduce one the source does not admit. Member order
     * is not compared -- it is preserved for deterministic output, not semantically significant.
     *
     * <p>{@link #profile} narrows along the one relation its family carries ({@link EnumProfile}):
     * {@link EnumProfile#IDENTIFIER} is inside {@link EnumProfile#TEXT}, so a refinement may withdraw the
     * latitude and never grant it. A refinement that does withdraw it still faces {@link #coherenceCheck}
     * on the merged body, which is what makes the members answer for the narrower profile.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof EnumBody other)) {
            return List.of("refines an enum with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkSubset(violations, "members", members, other.members);
        if (profile == EnumProfile.IDENTIFIER && other.profile == EnumProfile.TEXT) {
            violations.add("profile " + other.profile + " widens the source's own " + profile
                    + "; a refinement may withdraw the latitude of TEXT but never grant it");
        }
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Under {@link EnumProfile#IDENTIFIER} every member must satisfy [TSON-DATA] §7.7's grammar. This is
     * the body judged against itself in the way the interface describes: the declaration says the members
     * are names, and a member that is not one contradicts it.
     *
     * <p>Under {@link EnumProfile#TEXT} there is nothing to check -- any text is a member, and the
     * uniqueness and non-emptiness rules belong to the member set's own {@code set_type} contract rather
     * than here.
     */
    @Override
    public List<String> coherenceCheck() {
        if (profile != EnumProfile.IDENTIFIER) {
            return List.of();
        }
        List<String> violations = new ArrayList<>();
        for (String member : members) {
            IdentifierProfile.validate(member).ifPresent(why -> violations.add("member " + why
                    + " -- an enum whose members are not names declares 'profile: TEXT'"));
        }
        return List.copyOf(violations);
    }
}
