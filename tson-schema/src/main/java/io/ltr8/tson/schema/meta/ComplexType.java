package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * meta.tn1's {@code complex_type} constructor ({@code complex_type => ~atom & { component:
 * complex_component ~ NUMBER } }): {@code component} narrows the numeric family used for the
 * real/imaginary parts, defaulting to {@code NUMBER} (the exact-decimal tier). Pure constraint
 * value, no parsing/validation behavior -- deliberately no {@code tson-parser} parser exists for
 * this atom yet (added as a {@code schema.meta}/{@link Atom} variant only, per explicit user
 * direction, so {@code !complex_type {}}/{@code complex}'s own resolution succeeds -- not to add
 * real complex-number validation).
 *
 * <p>{@code component}'s own schema-level default ({@code ~ NUMBER}) is filled in by {@code
 * tson-parser}'s compiled {@code Record*Reader} from the schema itself before binding, the same
 * mechanism {@link FloatType}'s own defaulted {@code boolean} fields rely on -- an enum-typed field
 * binds from the resulting bare token by ordinary name matching, the same way {@link
 * FloatType#format} already does.
 *
 * <p>{@link Component} mirrors {@code complex_component}'s own five members (meta.tn1: {@code
 * complex_component => !enum [INTEGER NUMBER RATIONAL FLOAT32 FLOAT64]}) -- a plain nested enum,
 * not a {@code typeName()}-mapped one like {@link FloatType.Format}/{@link BinaryType.Encoding},
 * since nothing here multiplexes a single Java class across several built-in annotation names the
 * way those two do.
 */
@Typename(name = "complex_type")
public record ComplexType(Component component) implements Atom {

    public enum Component {
        INTEGER, NUMBER, RATIONAL, FLOAT32, FLOAT64
    }

    /** {@code complex => !complex_type {}} -- the unconstrained complex number, core.tn1's own {@code !complex}. */
    public static final ComplexType UNCONSTRAINED = new ComplexType(Component.NUMBER);
}
