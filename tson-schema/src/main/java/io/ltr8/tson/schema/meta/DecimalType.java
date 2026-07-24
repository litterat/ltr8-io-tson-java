package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The meta-kernel's {@code decimal_type} constructor (§5.6's {@code number} atom -- SQL's exact
 * tier, ISO/IEC 11404 {@code scaled}). Pure constraint values, no parsing/validation behavior --
 * {@code tson-parser}'s {@code DecimalParser} holds one of these and does the actual
 * reading/writing.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24, alongside the other 8 remaining atom
 * constraint-vocabulary families): {@code number => !decimal_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED} -- every field here is
 * {@code Optional}, needing no field-group-in-a-bound-instance design work, the same as {@link
 * IntegerType}/{@link TextType} before it.
 */
@Typename(name = "decimal_type")
public record DecimalType(
        Optional<BigDecimal> min,
        @Field("exclusive_min") Optional<BigDecimal> exclusiveMin,
        Optional<BigDecimal> max,
        @Field("exclusive_max") Optional<BigDecimal> exclusiveMax,
        @Field("multiple_of") Optional<BigDecimal> multipleOf,
        @Field("total_digits") Optional<Integer> totalDigits,
        @Field("fraction_digits") Optional<Integer> fractionDigits) implements Atom {

    /** {@code number => !decimal_type {}} -- the unconstrained exact number, §5.6's {@code !number}. */
    public static final DecimalType UNCONSTRAINED = new DecimalType(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());

    public DecimalType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }
}
