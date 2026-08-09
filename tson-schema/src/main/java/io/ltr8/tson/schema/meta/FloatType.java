package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code float_type} constructor (§5.6's {@code float32}/{@code float64} atoms
 * -- SQL's approximate tier, IEEE 754-2019). Pure constraint values, no parsing/validation behavior
 * -- {@code tson-compiler}'s {@code FloatParser} holds one of these and does the actual
 * reading/writing.
 *
 * <p>Also an {@link Atom} variant: {@code float32 => !float_type { format: BINARY32 } }/{@code
 * float64} are constructor-application instances (§5.5) whose resolved bodies are exactly {@link
 * #FLOAT32}/{@link #FLOAT64}.
 */
@Typename(name = "float_type")
public record FloatType(
        Format format,
        Optional<BigDecimal> min,
        @Field("exclusive_min") Optional<BigDecimal> exclusiveMin,
        Optional<BigDecimal> max,
        @Field("exclusive_max") Optional<BigDecimal> exclusiveMax,
        @Field("allow_nan") boolean allowNan,
        @Field("allow_infinity") boolean allowInfinity,
        @Field("allow_subnormal") boolean allowSubnormal,
        @Field("allow_negative_zero") boolean allowNegativeZero) implements Atom {

    /** {@code ieee_format}'s two members §5.6 actually promotes to built-in annotations; meta.tn1 also defines BINARY16/128/256 and the decimal128-family formats, unused until a schema (Part 2) refines float_type with one of them. */
    public enum Format {
        BINARY32("float32"), BINARY64("float64");

        private final String typeName;

        Format(String typeName) {
            this.typeName = typeName;
        }

        /** §5.6's built-in annotation name for this format, e.g. {@code !float32}. */
        public String typeName() {
            return typeName;
        }
    }

    /** {@code float32 => !float_type { format: BINARY32 } }; {@code float64} is the BINARY64 twin -- every other field left at its default ({@code ~ true} / absent). */
    public static final FloatType FLOAT32 = unconstrained(Format.BINARY32);
    public static final FloatType FLOAT64 = unconstrained(Format.BINARY64);

    private static FloatType unconstrained(Format format) {
        return new FloatType(format, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                true, true, true, true);
    }

    /** §5.6's built-in annotation name for this instance's {@link #format}, e.g. {@code !float32}. */
    public String typeName() {
        return format.typeName();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The four {@code allow_*} flags are permissions: a refinement may withdraw one (turning a
     * value the source admitted into a rejected one) but never grant one back, so {@code true} under
     * a {@code false} source is the violation and every other combination narrows.
     *
     * <p>{@link #format} is left unchecked, as a selector rather than an ordered facet -- see {@link
     * ComplexType} for the reasoning and the spec citation. It is also the one facet a refinement is
     * least able to reach in practice: core.tn declares no unformatted {@code float} instance to
     * refine, only {@code float32}/{@code float64}, each already carrying its own format.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof FloatType other)) {
            return List.of("refines a float with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkLower(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(other.min, other.exclusiveMin, "min", "exclusive_min"));
        AtomNarrowing.checkUpper(violations, AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"),
                AtomNarrowing.bound(other.max, other.exclusiveMax, "max", "exclusive_max"));
        AtomNarrowing.checkOnlyWithdraws(violations, "allow_nan", allowNan, other.allowNan);
        AtomNarrowing.checkOnlyWithdraws(violations, "allow_infinity", allowInfinity, other.allowInfinity);
        AtomNarrowing.checkOnlyWithdraws(violations, "allow_subnormal", allowSubnormal, other.allowSubnormal);
        AtomNarrowing.checkOnlyWithdraws(violations, "allow_negative_zero", allowNegativeZero, other.allowNegativeZero);
        return List.copyOf(violations);
    }

    public FloatType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }
}
