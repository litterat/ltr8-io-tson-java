package io.ltr8.tson.schema.meta;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The meta-kernel's {@code decimal_type} constructor (§5.6's {@code number} atom -- SQL's exact
 * tier, ISO/IEC 11404 {@code scaled}). Pure constraint values, no parsing/validation behavior --
 * {@code tson-parser}'s {@code DecimalParser} holds one of these and does the actual
 * reading/writing.
 */
public record DecimalType(
        Optional<BigDecimal> min,
        Optional<BigDecimal> exclusiveMin,
        Optional<BigDecimal> max,
        Optional<BigDecimal> exclusiveMax,
        Optional<BigDecimal> multipleOf,
        Optional<Integer> totalDigits,
        Optional<Integer> fractionDigits) {

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
