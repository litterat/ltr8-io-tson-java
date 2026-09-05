package io.ltr8.tson.schema.meta;

import io.ltr8.tson.schema.atom.CidrNetwork;

import java.util.ArrayList;
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
            CidrNetwork network = CidrNetwork.parse(entry, familyBits);
            if (network == null || !network.hostBitsAreZero()) {
                out.add(facet + " lists '" + entry + "', which is not an IPv" + (familyBits == 32 ? "4" : "6")
                        + " network -- expected CIDR notation, an address followed by '/' and a prefix length "
                        + "of 0-" + familyBits + ", with zero host bits beyond the prefix");
            }
        }
    }

    /**
     * The address families' emptiness rule: {@code within} and {@code excluding} must leave a value between
     * them. {@code within: ["10.0.0.0/8"]} beside {@code excluding: ["10.0.0.0/8"]} admits nothing, which is
     * {@code { min: 10 max: 3 }}'s exact twin -- two individually legal facets that between them describe no
     * value -- and is a pair an author reaches by editing rather than by trying.
     *
     * <p><b>Cover over a prefix tree is counting, not searching.</b> Two CIDR blocks are nested or disjoint
     * and never partly overlapping, so an exclusion meeting a permitted block either contains it (the block
     * is gone) or lies wholly within one of its halves. {@link #shortestSurviving} descends on that, and the
     * walk is exact, total, and bounded by the address width -- there is no approximation here to declare.
     *
     * <p><b>The prefix bounds are folded in, and have to be.</b> A network family's value is a block, and a
     * block is refused for <em>overlapping</em> an exclusion rather than only for being covered by one --
     * so {@code within: ["10.0.0.0/24"] excluding: ["10.0.0.5/32"] max_prefix: 24} admits nothing while
     * almost every address in the range survives. Judging the two facets alone would call that body
     * coherent. This is the same fold {@code integer} already does with its {@code size}-derived range.
     *
     * @param noun what the family's values are, for the message: an address, or a network
     */
    static void checkAdmitsAValue(List<String> out, String noun, List<String> within, List<String> excluding,
            int familyBits) {
        checkAdmitsAValue(out, noun, within, excluding, Optional.empty(), Optional.empty(), familyBits);
    }

    /** {@link #checkAdmitsAValue(List, String, List, List, int)} for a family that also bounds the prefix length. */
    static void checkAdmitsAValue(List<String> out, String noun, List<String> within, List<String> excluding,
            Optional<Integer> minPrefix, Optional<Integer> maxPrefix, int familyBits) {
        int floor = minPrefix.orElse(0);
        int ceiling = maxPrefix.orElse(familyBits);
        if (floor < 0 || ceiling > familyBits || floor > ceiling) {
            return; // an inverted or out-of-range bound is its own check's to report, and explains the body
        }
        List<CidrNetwork> permitted = networks(within, familyBits);
        List<CidrNetwork> excluded = networks(excluding, familyBits);
        if (permitted == null || excluded == null) {
            return; // a malformed entry is checkNetworks' to report, and there is nothing to judge under it
        }
        if (permitted.isEmpty()) {
            permitted = List.of(new CidrNetwork(new byte[familyBits / 8], 0)); // no `within` permits everything
        }
        // The shortest surviving block is the most permissive one, so it is the only one worth testing: any
        // value has to sit inside some survivor, and a longer survivor bounds the prefix length harder.
        int shortest = -1;
        for (CidrNetwork block : permitted) {
            int surviving = shortestSurviving(block, excluded);
            if (surviving >= 0 && (shortest < 0 || surviving < shortest)) {
                shortest = surviving;
            }
        }
        if (shortest >= 0 && Math.max(floor, shortest) <= ceiling) {
            return;
        }
        out.add(shortest < 0
                ? "within and excluding admit no " + noun + ": excluding covers every network within permits"
                : "within and excluding admit no " + noun + " of a permitted prefix length: the largest block "
                        + "they leave is a /" + shortest + ", and max_prefix is " + ceiling);
    }

    /**
     * The shortest prefix length among the maximal blocks inside {@code block} that no exclusion meets, or
     * {@code -1} where the block is covered outright. Shortest because a shorter prefix is a bigger block:
     * it is the survivor that constrains a permitted prefix length least.
     */
    private static int shortestSurviving(CidrNetwork block, List<CidrNetwork> excluded) {
        List<CidrNetwork> inside = new ArrayList<>();
        for (CidrNetwork exclusion : excluded) {
            if (exclusion.contains(block)) {
                return -1;
            }
            if (block.contains(exclusion)) {
                inside.add(exclusion);
            }
        }
        if (inside.isEmpty()) {
            return block.prefixLength();
        }
        // Nothing here is equal to the block (that returned above) and nothing is disjoint from it, so every
        // remaining exclusion is strictly longer -- the block is splittable, and each half faces its own share.
        int shortest = -1;
        for (CidrNetwork half : block.halves()) {
            int surviving = shortestSurviving(half, inside);
            if (surviving >= 0 && (shortest < 0 || surviving < shortest)) {
                shortest = surviving;
            }
        }
        return shortest;
    }

    /** The facet's entries as networks, or {@code null} where any one of them is not a network of this family. */
    private static List<CidrNetwork> networks(List<String> entries, int familyBits) {
        List<CidrNetwork> parsed = new ArrayList<>(entries.size());
        for (String entry : entries) {
            CidrNetwork network = CidrNetwork.parse(entry, familyBits);
            if (network == null || !network.hostBitsAreZero()) {
                return null;
            }
            parsed.add(network);
        }
        return parsed;
    }
}
