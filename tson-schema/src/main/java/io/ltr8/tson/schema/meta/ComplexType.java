package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.List;

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
 * not a {@code typeName()}-mapped one like {@link FloatType.Format}, since nothing here multiplexes a
 * single Java class across several built-in annotation names the way that one does. (The binary family's
 * alphabet did too, and is no longer a facet at all -- it is {@code @bytes_encoding}'s, and its enum lives
 * with the parser that reads it.)
 */
@Typename(name = "complex_type")
public record ComplexType(Component component) implements Atom {

    public enum Component {
        INTEGER, NUMBER, RATIONAL, FLOAT32, FLOAT64;

        /** Whether every value of this component type is a value of {@code wider} -- the order above. */
        boolean within(Component wider) {
            return switch (this) {
                case INTEGER -> wider == NUMBER || wider == RATIONAL;
                case NUMBER -> wider == RATIONAL;
                case FLOAT32 -> wider == FLOAT64;
                case RATIONAL, FLOAT64 -> false;
            };
        }
    }

    /** {@code complex => !complex_type {}} -- the unconstrained complex number, core.tn's own {@code !complex}. */
    public static final ComplexType UNCONSTRAINED = new ComplexType(Component.NUMBER);

    /**
     * {@inheritDoc}
     *
     * <p><b>A refinement may move {@link #component} only to a member whose value set is a subset of the
     * source's.</b> The five members are a partial order rather than a chain, so neither of §5.7's simpler
     * answers fits: they are not progressively smaller (an ordered bound's rule), and they are not
     * interchangeable spellings of one value space (a {@code bytes_type.encoding}'s rule).
     *
     * <pre>
     *   INTEGER  &#8834;  NUMBER  &#8834;  RATIONAL        the exact tiers, nested
     *   FLOAT32  &#8834;  FLOAT64                    the approximate tiers, nested
     *   the two families are incomparable
     * </pre>
     *
     * <p>So {@code !complex ^ { component: INTEGER }} is a real narrowing -- Gaussian integers
     * &#8484;+&#8484;i sit inside the exact-decimal complexes -- and its IS-A holds. {@code !complex ^
     * { component: FLOAT64 }} is not: binary64 carries &#177;inf and NaN that no exact decimal represents,
     * so a float64 complex is not a {@code complex}, and the IS-A the refinement claims is false. core.tn's
     * {@code @doc} documents both spellings side by side, and only the first of them is a refinement.
     *
     * <p><b>Stated over effective values, which is what makes it enforceable.</b> §5.7's own selector clause
     * turns on whether the source <em>wrote</em> the facet or took the default, and resolved output cannot
     * tell those apart. This compares the two components, so a refinement of a defaulted source and of an
     * explicitly-bound one get the same answer -- which is also the right answer, the source denoting the
     * same value set either way.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof ComplexType other)) {
            return List.of("refines a complex with " + refined.getClass().getSimpleName());
        }
        if (component == other.component || other.component.within(component)) {
            return List.of();
        }
        return List.of("component " + other.component + " is not a subset of the source's own " + component
                + "; a refinement narrows, and these name value sets neither of which contains the other"
                + " -- declare a new type instead (!complex_type { component: " + other.component + " })");
    }
}
