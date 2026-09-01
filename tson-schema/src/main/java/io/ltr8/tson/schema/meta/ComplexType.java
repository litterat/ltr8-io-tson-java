package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * meta.tn's {@code complex_type} constructor ({@code complex_type => ~atom & { component:
 * complex_component ~ NUMBER } }): {@code component} narrows the numeric family used for the
 * real/imaginary parts, defaulting to {@code NUMBER} (the exact-decimal tier). Pure constraint
 * value, no parsing/validation behavior -- {@code tson-compiler}'s {@code ComplexParser} does the
 * actual reading/writing (it takes no configuration from here: {@code component} is fixed, not
 * modeled -- see that class's own Javadoc).
 *
 * <p>{@code component}'s own schema-level default ({@code ~ NUMBER}) is filled in by {@code
 * tson-compiler}'s compiled {@code Record*Reader} from the schema itself before binding, the same
 * mechanism {@link FloatType}'s own defaulted {@code boolean} fields rely on -- an enum-typed field
 * binds from the resulting bare token by ordinary name matching, the same way {@link
 * FloatType#format} already does.
 *
 * <p>{@link Component} mirrors {@code complex_component}'s own five members (meta.tn: {@code
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

    /** {@code complex => !complex_type {}} -- the unconstrained complex number, core.tn's own {@code !complex}. */
    public static final ComplexType UNCONSTRAINED = new ComplexType(Component.NUMBER);

    /**
     * {@inheritDoc}
     *
     * <p><b>No narrowing check.</b> {@link #component} is this family's only facet and it is a
     * selector, not an ordered constraint -- the five members name different value sets rather than
     * progressively smaller ones. Treating it as an identity facet a refinement may restate but not
     * swap would reject core.tn's own documented usage, which calls {@code !complex ^ { component:
     * FLOAT64 } } a narrowing of a {@code component: NUMBER} source even though approximate binary64
     * values are not a subset of the exact tier. §5.7's per-facet rule settles it: a selector "may be set
     * where the source leaves it at the constructor's default ... and is thereafter identity-only", which is
     * exactly core.tn's usage, so nothing here narrows in the ordered sense.
     */
}
