package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An instance of the meta-kernel's {@code enum_type} constructor, resolved (Part 2 §7.4, §8.1): the {@code
 * name_type} its members are drawn from, an identifier family, and the {@code members} it lists. Backs {@code
 * boolean} ({@code [true false]}), the kernel's own internal enumerations ({@code product_access_type}, {@code
 * field_role}, ...), and every {@code !enum [...]} and {@code !enum_type { ... }} instance, including those of a
 * meta layer's own {@code enum_type} tightenings. Kept as an ordered {@code List}, matching how {@link
 * TypeDefinition#supertypes} represents a conceptual set -- member order is preserved for deterministic output,
 * not semantically significant.
 *
 * <p><b>Members are held as text whatever {@code name_type} is</b>, so every enum is this one shape. What it
 * decides -- that each member is a value of it, distinct under its equality, and the profile its members are
 * judged under as names -- needs its own definition, which lives in a namespace this body cannot see, so it is
 * checked at linking rather than by {@link #coherenceCheck}. A record's {@code name_type} is the same mechanism
 * over its field names.
 */
@Typename(name = "enum")
public record EnumBody(@Field("name_type") String nameType, List<String> members) implements Atom {

    /** The name type {@code enum} pins: an enumeration of identifiers. */
    public static final String IDENTIFIER = "identifier";

    @Record
    public EnumBody {
        Objects.requireNonNull(nameType, "nameType");
        members = List.copyOf(members);
    }

    /** An enumeration of identifiers -- {@code !enum [...]} -- for the callers that build one and say nothing else. */
    public EnumBody(List<String> members) {
        this(IDENTIFIER, members);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An enum's value set is written out in full, so narrowing is plain subset containment: a refinement may
     * drop members but never introduce one the source does not admit. Member order is not compared -- it is
     * preserved for deterministic output, not semantically significant. {@code name_type} is fixed at construction:
     * a refinement restating it names the same type, since narrowing the type would re-judge members already
     * admitted and widening it would admit labels the source's type refuses.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof EnumBody other)) {
            return List.of("refines an enum with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        if (!nameType.equals(other.nameType)) {
            violations.add("name_type '" + other.nameType + "' replaces the source's own '" + nameType
                    + "'; an enum's name_type is fixed where it is constructed, and a refinement narrows its members");
        }
        AtomNarrowing.checkSubset(violations, "members", members, other.members);
        return List.copyOf(violations);
    }
}
