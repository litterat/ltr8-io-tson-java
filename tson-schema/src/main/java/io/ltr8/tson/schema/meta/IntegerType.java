package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code integer_type} constructor (Part 2 §5.6/§9): the integer family's atom
 * constraint vocabulary -- bit width/signedness (via {@link IntegerSize}), bounds, and a
 * multiple-of constraint. Pure constraint values, no parsing or validation behavior -- {@code
 * tson-compiler}'s {@code IntegerParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant: {@code integer => !integer_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly this shape, bound the same way every other {@link
 * Top} variant round-trips through generic binding. Mutual exclusion between {@code min}/{@code
 * exclusiveMin} and between {@code max}/{@code exclusiveMax} is enforced by this record's own
 * compact constructor, not a separate wrapper.
 *
 * <p>The canonical (compact) constructor carries an explicit {@code @Record} -- required as soon
 * as a record has more than one public constructor (the convenience {@link
 * #IntegerType(IntegerSize)} one below is the second): {@code tson-bind}'s {@code
 * DefaultRecordBinder.getConstructor} only auto-picks a bare class's sole constructor when exactly
 * one exists, and throws {@code CodeAnalysisException} ("Could not find constructor") otherwise
 * unless one is explicitly marked.
 */
@Typename(name = "integer_type")
public record IntegerType(
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
        members = members.map(List::copyOf);
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
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

    /** {@code positive_integer => !integer ^ { min: 1 } }. */
    public static IntegerType ofMin(BigInteger min) {
        return new IntegerType(Optional.empty(), Optional.of(min), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /** {@code negative_integer => !integer ^ { max: -1 } }. */
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
     * floor and ceiling -- the tighter of its explicit bound and the one its width implies. That is
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
     * bounded-but-unsized source ({@code positive_integer}, {@code min: 1}) genuinely narrows even
     * though the width's range on its own reaches below that floor.
     *
     * <p>A width whose {@code bits} exceeds 4096 contributes no derived range -- materialising a
     * bound for it would allocate an arbitrarily large {@link BigInteger} from a single schema
     * declaration. No built-in width comes close (the ladder tops out at 256).
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof IntegerType other)) {
            return List.of("refines an integer with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkLower(violations, effectiveLower(), AtomNarrowing.bound(other.min, other.exclusiveMin,
                "min", "exclusive_min"));
        AtomNarrowing.checkUpper(violations, effectiveUpper(), AtomNarrowing.bound(other.max, other.exclusiveMax,
                "max", "exclusive_max"));
        AtomNarrowing.checkLower(violations, sizeLower(size), sizeLower(other.size));
        AtomNarrowing.checkUpper(violations, sizeUpper(size), sizeUpper(other.size));
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
     * <p>Judged on the <em>effective</em> range, the same fold {@link #constraintsCheck} compares
     * against: a stated bound and the one {@link #size} implies contradict each other exactly as
     * readily as two stated bounds do, so {@code !integer_type { size: { bits: 8  signed: false }
     * min: 300 }} is caught by the same comparison that catches {@code min: 10  max: 3}. This is the
     * one family where a body can be empty without stating both ends.
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
     * unsound rather than merely undiagnosed. A zero {@link IntegerSize#bits} needs no rule of its
     * own -- it contributes no derived range (below {@code MAX_DERIVED_BITS}'s positive floor), and a
     * width-zero integer is already unrepresentable rather than incoherent.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkRange(violations, effectiveLower(), effectiveUpper());
        AtomCoherence.checkPositiveStep(violations, "multiple_of", multipleOf, BigInteger::signum);
        AtomCoherence.checkMembers(violations, members.orElse(List.of()), effectiveLower(), effectiveUpper());
        return List.copyOf(violations);
    }

    /** The widest {@link IntegerSize#bits} a derived range is materialised for -- see {@link #constraintsCheck}. */
    private static final int MAX_DERIVED_BITS = 4096;

    /** The tighter of this type's stated floor and the one {@link #size} implies, {@code null} when unbounded below. */
    private AtomNarrowing.Bound<BigInteger> effectiveLower() {
        return AtomNarrowing.tighterLower(sizeLower(size), AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"));
    }

    /** The {@link #effectiveLower} twin. */
    private AtomNarrowing.Bound<BigInteger> effectiveUpper() {
        return AtomNarrowing.tighterUpper(sizeUpper(size), AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"));
    }

    private static AtomNarrowing.Bound<BigInteger> sizeLower(Optional<IntegerSize> size) {
        return derivedBits(size)
                .map(bits -> new AtomNarrowing.Bound<>(
                        size.get().signed() ? BigInteger.ONE.shiftLeft(bits - 1).negate() : BigInteger.ZERO, true, "size"))
                .orElse(null);
    }

    private static AtomNarrowing.Bound<BigInteger> sizeUpper(Optional<IntegerSize> size) {
        return derivedBits(size)
                .map(bits -> BigInteger.ONE.shiftLeft(size.get().signed() ? bits - 1 : bits).subtract(BigInteger.ONE))
                .map(ceiling -> new AtomNarrowing.Bound<>(ceiling, true, "size"))
                .orElse(null);
    }

    /** A width's bit count when it is small enough to materialise a range for, per {@link #MAX_DERIVED_BITS}. */
    private static Optional<Integer> derivedBits(Optional<IntegerSize> size) {
        return size.map(IntegerSize::bits)
                .filter(bits -> bits.signum() > 0 && bits.compareTo(BigInteger.valueOf(MAX_DERIVED_BITS)) <= 0)
                .map(BigInteger::intValueExact);
    }
}
