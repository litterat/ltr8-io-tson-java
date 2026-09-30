package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.List;

/**
 * An instance of an {@code enum_of<T>} application's vocabulary, resolved (Part 2 §7.4, §8.1): the {@code
 * members: set<T>} it lists. Backs {@code boolean} ({@code [true false]}), the kernel's own internal
 * enumerations ({@code product_access_type}, {@code field_role}, ...), and every {@code !enum [...]}, {@code
 * !text_enum [...]} and meta-layer enum. Kept as an ordered {@code List}, matching how {@link
 * TypeDefinition#supertypes} represents a conceptual set -- member order is preserved for deterministic
 * output, not semantically significant.
 *
 * <p><b>The label type is not a component.</b> Every enum built by one constructor shares that constructor's
 * {@code T}, so {@code T} is a fact about the constructor, recorded in its {@code source} and in its
 * {@code members} element type, and read from there where it is needed. Members conform to it structurally:
 * the constructor's reader reads each one as a {@code T}, so a member {@code T} refuses never reaches a body.
 */
@Typename(name = "enum")
public record EnumBody(List<String> members) implements Atom {

    public EnumBody {
        members = List.copyOf(members);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An enum's value set is written out in full, so narrowing is plain subset containment: a
     * refinement may drop members but never introduce one the source does not admit. Member order
     * is not compared -- it is preserved for deterministic output, not semantically significant.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof EnumBody other)) {
            return List.of("refines an enum with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkSubset(violations, "members", members, other.members);
        return List.copyOf(violations);
    }
}
