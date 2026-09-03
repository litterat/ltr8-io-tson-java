package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * Shared facet-comparison utilities behind {@link Atom#constraintsCheck} -- the mechanics every
 * atom family's own narrowing rule reuses (bounds, lengths, permission flags, member sets), so each
 * family only has to say <em>which</em> of its own fields are which kind of facet, not how a facet
 * is compared.
 *
 * <p>There is deliberately no helper for a <em>selector</em> facet ({@code complex_type.component},
 * {@code float_type.format}, {@code uuid_type.version}): a selector picks among unordered
 * alternatives, so no comparison decides whether swapping one narrows. {@link ComplexType} records
 * why those are left unchecked rather than pinned.
 *
 * <p>Every {@code check*} method appends a human-readable violation fragment to {@code out} and
 * appends nothing when the refinement is a valid tightening, so a family's rule reads as a straight
 * list of facet checks and reports all of its problems at once rather than only the first.
 *
 * <p>The comparison direction is always "is the refined facet at least as restrictive as the
 * source's own?" -- §5.7's refinement rule, where a refinement tightens and never loosens. A facet
 * absent from the source is unbounded and admits any refined value.
 *
 * <p>A facet absent from the <em>refinement</em> is likewise not a violation, because a refinement
 * has no way to express one: {@code DefinitionResolver}'s merge gives an unmentioned facet the
 * source's own value, so an absent refined facet means the source never carried it either. The
 * exception is a bound a family <em>derives</em> rather than stores -- an integer's own {@code
 * size} implies a range with no {@code min}/{@code max} facet behind it -- where absent is the
 * normal, correct state and complaining would reject every valid refinement of a sized integer.
 */
final class AtomNarrowing {

    private AtomNarrowing() {
    }

    /**
     * One end of a range as a comparable value plus whether it is inclusive, paired with the wire
     * facet name it came from so a violation can name the field the author actually wrote. An
     * inclusive/exclusive pair ({@code min}/{@code exclusive_min}) collapses to this one shape, so
     * a bound comparison never has to branch on which of the two a family happened to use.
     */
    record Bound<T extends Comparable<? super T>>(T value, boolean inclusive, String facet) {

        Bound {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return facet + " " + value;
        }
    }

    /**
     * The single bound an inclusive/exclusive facet pair denotes, or {@code null} when the range is
     * open at that end. Both ends collapse the same way, so one method serves {@code min}/{@code
     * exclusive_min} and {@code max}/{@code exclusive_max} alike -- the call site's own variable
     * name says which end it built.
     */
    static <T extends Comparable<? super T>> Bound<T> bound(Optional<T> inclusive, Optional<T> exclusive, String inclusiveFacet,
            String exclusiveFacet) {
        if (inclusive.isPresent()) {
            return new Bound<>(inclusive.get(), true, inclusiveFacet);
        }
        return exclusive.map(value -> new Bound<>(value, false, exclusiveFacet)).orElse(null);
    }

    /**
     * Whether {@code refined} is a lower bound at least as restrictive as {@code source} -- a
     * higher floor, or the same floor made exclusive. Equal bounds of equal strictness tighten
     * vacuously, which is what lets a refinement restate a facet it doesn't actually change.
     */
    static <T extends Comparable<? super T>> boolean lowerTightens(Bound<T> source, Bound<T> refined) {
        int order = refined.value().compareTo(source.value());
        return order != 0 ? order > 0 : !refined.inclusive() || source.inclusive();
    }

    /** The {@link #lowerTightens} twin: a lower ceiling, or the same ceiling made exclusive. */
    static <T extends Comparable<? super T>> boolean upperTightens(Bound<T> source, Bound<T> refined) {
        int order = refined.value().compareTo(source.value());
        return order != 0 ? order < 0 : !refined.inclusive() || source.inclusive();
    }

    /**
     * The tighter of two lower bounds -- how a family folds an implied range, such as an integer's
     * own {@code size}, into its explicit one before comparing. A {@code null} end is unbounded, so
     * the other one wins.
     */
    static <T extends Comparable<? super T>> Bound<T> tighterLower(Bound<T> left, Bound<T> right) {
        if (left == null || right == null) {
            return left == null ? right : left;
        }
        return lowerTightens(left, right) ? right : left;
    }

    /** The {@link #tighterLower} twin, for the upper end. */
    static <T extends Comparable<? super T>> Bound<T> tighterUpper(Bound<T> left, Bound<T> right) {
        if (left == null || right == null) {
            return left == null ? right : left;
        }
        return upperTightens(left, right) ? right : left;
    }

    /** A refined lower bound must not sit below the source's own -- {@code min: -10} under a source whose floor is 0. */
    static <T extends Comparable<? super T>> void checkLower(List<String> out, Bound<T> source, Bound<T> refined) {
        if (source != null && refined != null && !lowerTightens(source, refined)) {
            out.add(refined + " is below the source's own " + source);
        }
    }

    /** A refined upper bound must not sit above the source's own -- {@code max: 300} under a source whose ceiling is 255. */
    static <T extends Comparable<? super T>> void checkUpper(List<String> out, Bound<T> source, Bound<T> refined) {
        if (source != null && refined != null && !upperTightens(source, refined)) {
            out.add(refined + " is above the source's own " + source);
        }
    }

    /** A floor-style facet ({@code min_length}, {@code min_prefix}) may only rise. */
    static <T extends Comparable<? super T>> void checkAtLeast(List<String> out, String facet, Optional<T> source, Optional<T> refined) {
        if (source.isPresent() && refined.isPresent() && refined.get().compareTo(source.get()) < 0) {
            out.add(facet + " " + refined.get() + " is below the source's own " + source.get());
        }
    }

    /** A ceiling-style facet ({@code max_length}, {@code max_prefix}, {@code total_digits}) may only fall. */
    static <T extends Comparable<? super T>> void checkAtMost(List<String> out, String facet, Optional<T> source, Optional<T> refined) {
        if (source.isPresent() && refined.isPresent() && refined.get().compareTo(source.get()) > 0) {
            out.add(facet + " " + refined.get() + " is above the source's own " + source.get());
        }
    }

    /** A permission flag ({@code allow_nan} and friends) may be withdrawn but never granted back. */
    static void checkOnlyWithdraws(List<String> out, String facet, boolean source, boolean refined) {
        if (refined && !source) {
            out.add(facet + " re-enables what the source forbids");
        }
    }

    /**
     * A member/value set may only shrink -- an enum's own {@code members}, a numeric family's sparse
     * {@code members}, a CIDR family's {@code within}. §5.7 declares the member-set facet kind once and
     * never enum-specifically ("an enum's {@code members}, a pattern alternation authored as a set"), so
     * one comparison serves every family that carries one. Members are compared by {@code equals}, which
     * is [TSON-DATA] §4.3's identity where a member's host type has one value per number -- an identifier,
     * a {@link java.math.BigInteger}, so {@code 0x50} and {@code 80} are one member.
     */
    static <T> void checkSubset(List<String> out, String facet, List<T> source, List<T> refined) {
        checkSubset(out, facet, source, refined, Object::equals);
    }

    /**
     * The same rule where the family's own identity is not {@code equals} -- {@link java.math.BigDecimal}
     * carries its scale, so {@code 2.50} and {@code 2.5} are two objects and one member, and only the
     * family knows that. §4.3's identity is the one the read applies, so the two must not part company
     * here: a member set is admitted or refused by exactly what a value in it would be matched against.
     */
    static <T> void checkSubset(List<String> out, String facet, List<T> source, List<T> refined,
            BiPredicate<T, T> sameValue) {
        if (source.isEmpty()) {
            return;
        }
        List<T> added = refined.stream()
                .filter(member -> source.stream().noneMatch(admitted -> sameValue.test(admitted, member)))
                .toList();
        if (!added.isEmpty()) {
            out.add(facet + " adds " + added + ", which the source does not admit");
        }
    }
}
