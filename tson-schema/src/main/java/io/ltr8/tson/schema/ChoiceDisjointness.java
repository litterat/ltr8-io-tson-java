package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

/**
 * Derives a choice's {@code type_definition.disjoint} (Part 2 §5.4): whether its variants are pairwise
 * disjoint as value sets. The encoding-independent fact the resolver records -- {@code Optional<Boolean>}
 * with three states: {@code true} (proved disjoint), {@code false} (provably not disjoint, e.g. an IS-A
 * variant pair or overlapping numeric ranges), {@code empty} (neither proved -- conservative, per §5.4's
 * "leave the fact absent when it cannot").
 *
 * <p><b>Scope (deliberately partial, per §5.4).</b> The cheap, exact, spec-baseline rules only: different
 * kinds are disjoint; different atom families are disjoint; same-family integer atoms are compared by their
 * bound intervals; a variant that IS-A another (via its transitive supertypes) is not disjoint. Two cases
 * §5.4 marks a resolver MAY prove are left absent here: record-set disjointness under composition, and
 * pattern disjointness over {@code regex}-constrained atoms. The latter is not attempted even though {@code
 * tson-regex}'s {@code TsonRegex.isDisjointFrom} could decide it exactly -- two {@code regex}/{@code
 * text}-constrained atoms are the same string base-type class, and §5.4's own Tagging rule makes a shared
 * base-type class un-discriminable for TSON text regardless of value-set disjointness (`(email | uri)` is
 * the spec's example), so proving it would give TSON-text untagged reading nothing; it also keeps this
 * module free of a {@code tson-regex} dependency. See {@code BACKLOG.md} for the fuller rationale.
 */
final class ChoiceDisjointness {

    private enum Pair {DISJOINT, OVERLAP, UNKNOWN}

    private ChoiceDisjointness() {
    }

    static Optional<Boolean> derive(ChoiceBody choice, Map<String, TypeDefinition> namespace) {
        var variants = choice.variants();
        boolean allProvedDisjoint = true;
        for (int i = 0; i < variants.size(); i++) {
            for (int j = i + 1; j < variants.size(); j++) {
                switch (pairwise(variants.get(i), variants.get(j), namespace)) {
                    case OVERLAP -> {
                        return Optional.of(false); // one overlapping pair makes the whole choice not disjoint
                    }
                    case UNKNOWN -> allProvedDisjoint = false;
                    case DISJOINT -> { /* keep checking */ }
                }
            }
        }
        return allProvedDisjoint ? Optional.of(true) : Optional.empty();
    }

    private static Pair pairwise(TypeRef a, TypeRef b, Map<String, TypeDefinition> namespace) {
        TypeDefinition da = namespace.get(a.name());
        TypeDefinition db = namespace.get(b.name());
        if (da == null || db == null) {
            return Pair.UNKNOWN; // an unresolved variant -- defensive; the linker validates references separately
        }
        // IS-A either way (or the same type): one value set contains the other, so they share values.
        if (a.name().equals(b.name()) || da.supertypes().contains(b.name()) || db.supertypes().contains(a.name())) {
            return Pair.OVERLAP;
        }
        if (da.kind() != db.kind()) {
            return Pair.DISJOINT; // different kinds are disjoint (§5.4)
        }
        if (da.body() instanceof Atom atomA && db.body() instanceof Atom atomB) {
            return atomPair(atomA, atomB);
        }
        return Pair.UNKNOWN; // records/products/sums -- the labelled form is the better model (§5.4)
    }

    private static Pair atomPair(Atom a, Atom b) {
        if (a.getClass() != b.getClass()) {
            return Pair.DISJOINT; // different atom families are disjoint (§5.4)
        }
        if (a instanceof IntegerType ia && b instanceof IntegerType ib) {
            return integerPair(ia, ib);
        }
        // Same non-integer family (two text/regex, two enums, ...): pattern/set disjointness is a §5.4 MAY
        // this doesn't attempt (see the class Javadoc) -- leave it absent.
        return Pair.UNKNOWN;
    }

    /** Same-family integers, compared by bound interval (§5.4). {@code null} bound = unbounded. */
    private static Pair integerPair(IntegerType a, IntegerType b) {
        BigInteger loA = low(a);
        BigInteger hiA = high(a);
        BigInteger loB = low(b);
        BigInteger hiB = high(b);
        boolean aBelowB = hiA != null && loB != null && hiA.compareTo(loB) < 0;
        boolean bBelowA = hiB != null && loA != null && hiB.compareTo(loA) < 0;
        if (aBelowB || bBelowA) {
            return Pair.DISJOINT; // the intervals don't meet
        }
        // Intervals overlap. A multiple-of constraint could still separate them (evens vs odds), which this
        // interval-only rule doesn't decide, so only claim overlap when neither side carries one.
        if (a.multipleOf().isEmpty() && b.multipleOf().isEmpty()) {
            return Pair.OVERLAP;
        }
        return Pair.UNKNOWN;
    }

    private static BigInteger low(IntegerType t) {
        BigInteger fromSize = t.size().map(size -> sizeRange(size)[0]).orElse(null);
        BigInteger explicit = t.min().or(() -> t.exclusiveMin().map(m -> m.add(BigInteger.ONE))).orElse(null);
        return tighterLow(fromSize, explicit);
    }

    private static BigInteger high(IntegerType t) {
        BigInteger fromSize = t.size().map(size -> sizeRange(size)[1]).orElse(null);
        BigInteger explicit = t.max().or(() -> t.exclusiveMax().map(m -> m.subtract(BigInteger.ONE))).orElse(null);
        return tighterHigh(fromSize, explicit);
    }

    /** Inclusive {@code [min, max]} a {@code size} implies -- signed two's-complement or unsigned. */
    private static BigInteger[] sizeRange(IntegerSize size) {
        int bits = size.bits().intValueExact();
        if (size.signed()) {
            BigInteger half = BigInteger.TWO.pow(bits - 1);
            return new BigInteger[] {half.negate(), half.subtract(BigInteger.ONE)};
        }
        return new BigInteger[] {BigInteger.ZERO, BigInteger.TWO.pow(bits).subtract(BigInteger.ONE)};
    }

    private static BigInteger tighterLow(BigInteger a, BigInteger b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a.max(b);
    }

    private static BigInteger tighterHigh(BigInteger a, BigInteger b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a.min(b);
    }
}
