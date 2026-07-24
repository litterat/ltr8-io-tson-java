package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The meta-kernel's {@code float_type} constructor (§5.6's {@code float32}/{@code float64} atoms
 * -- SQL's approximate tier, IEEE 754-2019). Pure constraint values, no parsing/validation behavior
 * -- {@code tson-parser}'s {@code FloatParser} holds one of these and does the actual
 * reading/writing.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24): {@code float32 => !float_type { format:
 * BINARY32 } }/{@code float64} are constructor-application instances (§5.5) whose resolved bodies
 * are exactly {@link #FLOAT32}/{@link #FLOAT64}.
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

    public FloatType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }
}
