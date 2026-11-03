package io.ltr8.tson.schema.numeric;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.atom.AtomCoherence;
import io.ltr8.tson.schema.atom.AtomNarrowing;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The kernel's {@code integer_type} constructor (Part 2 §5.6/§9): the integer family's atom
 * constraint vocabulary -- bit width/signedness (via {@link IntegerSize}), bounds, and a
 * multiple-of constraint. Pure constraint values, no parsing or validation behavior -- {@code
 * tson-compiler}'s {@code IntegerParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant: {@code integer => !numeric.integer_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly this shape, bound the same way every other {@link
 * Top} variant round-trips through generic binding. Mutual exclusion between {@code min}/{@code
 * exclusiveMin} and between {@code max}/{@code exclusiveMax} is enforced by this record's own
 * compact constructor, not a separate wrapper.
 *
 * <p>The canonical (compact) constructor carries an explicit {@code @Record} -- required as soon
 * as a record has more than one public constructor (the convenience {@link
 * #IntegerType(IntegerSize)} one below is the second): {@code ltr8-bind}'s {@code
 * DefaultRecordBinder.getConstructor} only auto-picks a bare class's sole constructor when exactly
 * one exists, and throws {@code CodeAnalysisException} ("Could not find constructor") otherwise
 * unless one is explicitly marked.
 */
@Typename(name = "numeric.integer_type")
public record IntegerType(TypeChain chain,
        Optional<IntegerSize> size,
        Optional<BigInteger> min,
        @Field("exclusive_min") Optional<BigInteger> exclusiveMin,
        Optional<BigInteger> max,
        @Field("exclusive_max") Optional<BigInteger> exclusiveMax,
        @Field("multiple_of") Optional<BigInteger> multipleOf,
        Optional<List<BigInteger>> members) implements Atom {

    /** The kernel's unconstrained, arbitrary-precision {@code integer}. */
    public static final IntegerType UNCONSTRAINED = new IntegerType(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());

    @Record
    public IntegerType {
        chain = TypeChain.orEmpty(chain);
        members = members.map(List::copyOf);
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public IntegerType(Optional<IntegerSize> size, Optional<BigInteger> min, Optional<BigInteger> exclusiveMin,
            Optional<BigInteger> max, Optional<BigInteger> exclusiveMax, Optional<BigInteger> multipleOf,
            Optional<List<BigInteger>> members) {
        this(TypeChain.EMPTY, size, min, exclusiveMin, max, exclusiveMax, multipleOf, members);
    }

    @Override
    public IntegerType withChain(TypeChain chain) {
        return new IntegerType(chain, size, min, exclusiveMin, max, exclusiveMax, multipleOf, members);
    }

    /**
     * The constraint set without a member set -- every facet but {@link #members}, which most integer types
     * do not carry. The sparse member set is the newest facet and the rarest; spelling {@code
     * Optional.empty()} for it at every construction says nothing a reader needs.
     */
    public IntegerType(Optional<IntegerSize> size, Optional<BigInteger> min, Optional<BigInteger> exclusiveMin,
            Optional<BigInteger> max, Optional<BigInteger> exclusiveMax, Optional<BigInteger> multipleOf) {
        this(size, min, exclusiveMin, max, exclusiveMax, multipleOf, Optional.empty());
    }

    /** {@code int32 => !integer ^ { size: { bits: 32 signed: true } } } -- e.g. {@code new IntegerType(new IntegerSize(32, true))}. */
    public IntegerType(IntegerSize size) {
        this(Optional.of(size), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** {@code !integer ^ { min: 1 } } -- a lower bound and no width. */
    public static IntegerType ofMin(BigInteger min) {
        return new IntegerType(Optional.empty(), Optional.of(min), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /** {@code !integer ^ { max: -1 } } -- an upper bound and no width. */
    public static IntegerType ofMax(BigInteger max) {
        return new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(max), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The integer family is the one where a bound is not always written as a bound: {@link
     * #size} implies a range of its own ({@code int8} admits -128..127 while carrying no {@code
     * min}/{@code max} at all), so a stated bound is compared against the source's <em>effective</em>
     * floor and ceiling -- its explicit bound and the one its width implies, each in turn. That is
     * what makes {@code !uint8 ^ { min: -10  max: 300 }} the error it should be: {@code uint8}
     * states no bounds, but its width fixes 0..255, and both stated facets fall outside it.
     *
     * <p>A stated bound is compared against the source's effective range rather than the refinement's
     * own effective range on purpose. Intersecting first would make every widening vacuous -- {@code
     * min: -10} on a 8-bit unsigned type still yields 0..255, so the value sets would compare equal
     * and nothing would ever be rejected. §5.7 constrains what an author may <em>write</em>, so each
     * facet is judged on its own against what the source already guarantees.
     *
     * <p>{@code size} is checked against the source's own {@code size} alone, never against its
     * explicit bounds: the two compose by intersection within a single type, so adding a width to a
     * bounded-but-unsized source ({@code !integer ^ { min: 1 }}) genuinely narrows even
     * though the width's range on its own reaches below that floor.
     *
     * <p>A width's range is never materialised: every comparison against one is {@link IntegerSize}'s bit-length
     * test, exact and total for any {@code bits}.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof IntegerType other)) {
            return List.of("refines an integer with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.Bound<BigInteger> lower = other.statedLower();
        AtomNarrowing.Bound<BigInteger> upper = other.statedUpper();
        int before = violations.size();
        AtomNarrowing.checkLower(violations, statedLower(), lower);
        if (violations.size() == before && lower != null && size.isPresent() && size.get().isBelow(lower.value())) {
            violations.add(lower + " is below the source's own " + size.get().describe() + " size");
        }
        before = violations.size();
        AtomNarrowing.checkUpper(violations, statedUpper(), upper);
        if (violations.size() == before && upper != null && size.isPresent() && size.get().isAbove(upper.value())) {
            violations.add(upper + " is above the source's own " + size.get().describe() + " size");
        }
        if (size.isPresent() && other.size.isPresent() && !other.size.get().within(size.get())) {
            violations.add("size " + other.size.get().describe() + " is wider than the source's own "
                    + size.get().describe());
        }
        if (multipleOf.isPresent() && other.multipleOf.isPresent()
                && !other.multipleOf.get().remainder(multipleOf.get()).equals(BigInteger.ZERO)) {
            violations.add("multiple_of " + other.multipleOf.get() + " is not itself a multiple of the source's own "
                    + multipleOf.get());
        }
        AtomNarrowing.checkSubset(violations, "members", members.orElse(List.of()), other.members.orElse(List.of()));
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Judged on the <em>effective</em> range, the stated bounds with the one {@link #size} implies: a stated
     * bound and the width contradict each other exactly as readily as two stated bounds do, so {@code
     * !numeric.integer_type { size: { bits: 8  signed: false } min: 300 }} is caught as {@code min: 10  max: 3}
     * is, and a member outside the width as one outside a stated bound. This is the one family where a body can
     * be empty without stating both ends. The width is judged by {@link IntegerSize}'s bit-length test, so any
     * {@code bits} is checked exactly and none is materialised.
     *
     * <p>The fold is what {@link #constraintsCheck} deliberately does <em>not</em> do to the
     * refinement side, and the difference is not an inconsistency: there, intersecting first would
     * make every widening compare vacuously equal and nothing would ever be rejected. Here there is
     * no second body to compare against and no widening to hide -- the question is only whether what
     * this body says leaves any value standing, and an implied bound constrains as firmly as a
     * written one.
     *
     * <p>{@code multiple_of: 0} is rejected outright rather than treated as vacuous: see {@link
     * AtomCoherence#checkPositiveStep} for why it is the one incoherence here that is actively
     * unsound rather than merely undiagnosed. A {@link IntegerSize#bits} of zero or below is refused on
     * the same terms: {@code bits} is typed {@code integer}, since {@code numeric.count} refines {@code int64},
     * whose width is stated through it, and a width that is no width admits no value.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomNarrowing.Bound<BigInteger> lower = statedLower();
        AtomNarrowing.Bound<BigInteger> upper = statedUpper();
        AtomCoherence.checkRange(violations, lower, upper);
        AtomCoherence.checkPositiveStep(violations, "multiple_of", multipleOf, BigInteger::signum);
        if (size.isPresent()) {
            IntegerSize width = size.get();
            if (width.bits().signum() <= 0) {
                violations.add("size.bits " + width.bits() + " is not a width above zero");
            } else {
                // An exclusive end excludes the width's own end too: `exclusive_min: 255` leaves nothing of uint8.
                if (lower != null && width.isAbove(lower.inclusive() ? lower.value() : lower.value().add(BigInteger.ONE))) {
                    violations.add(lower + " is above the " + width.describe() + " size's ceiling");
                }
                if (upper != null
                        && width.isBelow(upper.inclusive() ? upper.value() : upper.value().subtract(BigInteger.ONE))) {
                    violations.add(upper + " is below the " + width.describe() + " size's floor");
                }
            }
        }
        List<BigInteger> listed = members.orElse(List.of());
        AtomCoherence.checkMembers(violations, listed, lower, upper);
        if (size.isPresent() && size.get().bits().signum() > 0) {
            for (BigInteger member : listed) {
                if (!size.get().admits(member)) {
                    violations.add("member " + member + " is outside the " + size.get().describe() + " size");
                }
            }
        }
        return List.copyOf(violations);
    }

    /** This type's stated floor, {@code min} or {@code exclusive_min}, {@code null} when it states none. */
    private AtomNarrowing.Bound<BigInteger> statedLower() {
        return AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min");
    }

    /** The {@link #statedLower} twin. */
    private AtomNarrowing.Bound<BigInteger> statedUpper() {
        return AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max");
    }
}
