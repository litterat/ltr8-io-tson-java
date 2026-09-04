package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * Shared facet-comparison utilities behind {@link Atom#coherenceCheck} and {@link
 * Product#coherenceCheck} -- the {@link AtomNarrowing} twin, for the other question. {@code
 * AtomNarrowing} compares two bodies (is this refinement a tightening of that source?); this compares
 * one body's facets against each other (does what the author wrote admit any value at all?), so a
 * family again only has to say <em>which</em> of its own fields form a range, not how a range is judged
 * empty.
 *
 * <p><b>Both base kinds reach it, which is the point of it being here rather than on either.</b> An
 * atom's {@code min}/{@code max} family and a container's {@code min_items}/{@code max_items} pair ask
 * the identical question of the identical shape; two implementations of it would be two places for one
 * to drift, and drift is exactly how {@code [text; 5..3]} came to be refused while the {@code !array {
 * ... }} body it denotes was accepted.
 *
 * <p>Every {@code check*} method appends a human-readable violation fragment to {@code out} and
 * appends nothing when the facets are coherent, matching {@code AtomNarrowing}'s convention so a
 * family's two rules read the same way and both report every problem rather than the first.
 *
 * <p>A facet absent from the body is unbounded and contradicts nothing -- an incoherence needs two
 * present facets (or one present facet and a range the family itself fixes). That is what keeps every
 * unconstrained instance in this package, and every partially-constrained one, silently coherent.
 *
 * <p><b>Emptiness is judged, not narrowness.</b> A range admitting exactly one value ({@code min: 5
 * max: 5}) is a legitimate way to pin a constant and passes; the same range made exclusive at either
 * end admits nothing and does not. So the strictness of each end is load-bearing here in a way a
 * simple {@code compareTo} would miss, which is why the bound-pair check takes {@link
 * AtomNarrowing.Bound}s rather than raw values.
 */
final class AtomCoherence {

    private AtomCoherence() {
    }

    /**
     * A lower and upper bound must leave something between them. Empty in two ways: the floor above
     * the ceiling outright, or the two meeting at a single value that an exclusive end then removes
     * -- {@code exclusive_min: 5 max: 5} admits nothing while {@code min: 5 max: 5} admits exactly 5.
     */
    static <T extends Comparable<? super T>> void checkRange(List<String> out, AtomNarrowing.Bound<T> lower,
            AtomNarrowing.Bound<T> upper) {
        if (lower == null || upper == null) {
            return;
        }
        int order = lower.value().compareTo(upper.value());
        if (order > 0) {
            out.add(lower + " is above " + upper);
        } else if (order == 0 && !(lower.inclusive() && upper.inclusive())) {
            out.add(lower + " and " + upper + " meet at " + lower.value() + ", which one of them excludes");
        }
    }

    /**
     * Every member of a sparse member set must satisfy the body's other facets. {@code { members: [80 443]
     *  max: 100 }} is {@code { min: 10  max: 3 }}'s exact twin -- two individually legal facets whose
     * conjunction admits nothing -- and §5.7 does not reach it, each facet tightening fine on its own. The
     * rule is per member and needs no set arithmetic; the range passed in is the family's <em>effective</em>
     * one, so a width a family derives rather than stores ({@code integer_type.size}) constrains a member
     * exactly as a stated bound does.
     */
    static <T extends Comparable<? super T>> void checkMembers(List<String> out, List<T> members,
            AtomNarrowing.Bound<T> lower, AtomNarrowing.Bound<T> upper) {
        for (T member : members) {
            if (lower != null && outsideLower(member, lower)) {
                out.add("member " + member + " is below " + lower);
            } else if (upper != null && outsideUpper(member, upper)) {
                out.add("member " + member + " is above " + upper);
            }
        }
    }

    private static <T extends Comparable<? super T>> boolean outsideLower(T member, AtomNarrowing.Bound<T> lower) {
        int order = member.compareTo(lower.value());
        return order < 0 || (order == 0 && !lower.inclusive());
    }

    private static <T extends Comparable<? super T>> boolean outsideUpper(T member, AtomNarrowing.Bound<T> upper) {
        int order = member.compareTo(upper.value());
        return order > 0 || (order == 0 && !upper.inclusive());
    }

    /**
     * A floor facet must not sit above its ceiling twin, for the families whose bounds are plain
     * inclusive values with no exclusive spelling -- {@code min_length}/{@code max_length}, {@code
     * min_prefix}/{@code max_prefix}, {@code fraction_digits} against {@code total_digits}.
     */
    static <T extends Comparable<? super T>> void checkOrdered(List<String> out, String lowerFacet, Optional<T> lower,
            String upperFacet, Optional<T> upper) {
        if (lower.isPresent() && upper.isPresent() && lower.get().compareTo(upper.get()) > 0) {
            out.add(lowerFacet + " " + lower.get() + " is above " + upperFacet + " " + upper.get());
        }
    }

    /**
     * A facet must fall inside the range the family itself fixes -- a CIDR prefix length within its
     * address family's width. Unlike every other check here this one needs no second facet: the range
     * is the family's, not something the author wrote, so a single out-of-range value is already a
     * contradiction of the type it claims to constrain.
     */
    static void checkWithin(List<String> out, String facet, Optional<Integer> value, int low, int high) {
        value.filter(v -> v < low || v > high)
                .ifPresent(v -> out.add(facet + " " + v + " is outside the family range " + low + "-" + high));
    }

    /**
     * A count-style facet may not be negative -- a length or a digit count below zero describes no
     * value. Kept separate from {@link #checkWithin} because the ceiling is the family's business and
     * the floor is not: every count shares zero, and no family has a meaningful maximum.
     */
    static void checkNonNegative(List<String> out, String facet, Optional<Integer> value) {
        value.filter(v -> v < 0).ifPresent(v -> out.add(facet + " " + v + " is negative"));
    }

    /**
     * A step facet must be a usable divisor. Zero is the case that matters and it is not merely
     * vacuous: {@code IntegerParser}/{@code DecimalParser} validate with {@code value.remainder(m)},
     * which throws {@link ArithmeticException} on a zero divisor, so an unchecked {@code multiple_of:
     * 0} turns every read of an otherwise valid document into a library-fault report against the
     * <em>data</em>. Catching it at the declaration puts the verdict on the schema that is actually
     * wrong.
     *
     * <p>A negative step is rejected alongside it. Nothing divides differently by {@code -2} than by
     * {@code 2}, so it is not unsound, but the sign is meaningless in a facet whose whole content is
     * a grid spacing -- writing one means the author meant something the field cannot express.
     */
    static <T> void checkPositiveStep(List<String> out, String facet, Optional<T> value, ToIntFunction<T> signum) {
        value.ifPresent(step -> {
            int sign = signum.applyAsInt(step);
            if (sign == 0) {
                out.add(facet + " is zero, which divides nothing");
            } else if (sign < 0) {
                out.add(facet + " " + step + " is negative; a step is a spacing, not a direction");
            }
        });
    }

    /**
     * Every entry of a {@code within}/{@code excluding} list must be a network of the family's own width.
     *
     * <p>The facets are typed {@code [value]} (meta.tn cannot name a network instance -- core declares those,
     * and core imports meta), so they arrive as text and the family that owns the rule is the only place that
     * can judge them. {@link io.ltr8.tson.schema.atom.CidrNetwork#parse} is the grammar, and it refuses a
     * malformed address, a prefix outside the family range, and nonzero host bits alike.
     */
    static void checkNetworks(List<String> out, String facet, List<String> entries, int familyBits) {
        for (String entry : entries) {
            io.ltr8.tson.schema.atom.CidrNetwork network =
                    io.ltr8.tson.schema.atom.CidrNetwork.parse(entry, familyBits);
            if (network == null || !network.hostBitsAreZero()) {
                out.add(facet + " lists '" + entry + "', which is not an IPv" + (familyBits == 32 ? "4" : "6")
                        + " network -- expected CIDR notation, an address followed by '/' and a prefix length "
                        + "of 0-" + familyBits + ", with zero host bits beyond the prefix");
            }
        }
    }
}
