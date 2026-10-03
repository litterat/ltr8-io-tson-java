package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code array} constructor's own vocabulary, resolved (Part 2 §4.2, §5.3,
 * §8.1) -- {@code access_pattern}/{@code size_type} are fixed ({@code INDEX}/{@code VARIABLE}) and
 * never appear in output. Also backs {@code set_type}, whose refinement resolves to this same shape with
 * different field values: it pins {@code voidable: false}, {@code ordered: false} and {@code unique_items: true}.
 * Bound through plain {@code TsonObjectWriter.toTson}, {@code voidable}/{@code ordered}/{@code uniqueItems} always
 * appear in written output even at their nominal default -- unlike a hand-written writer, generic record binding
 * has no notion of "this primitive value is the default, omit it".
 *
 * <p>{@code voidable} says whether the void sentinel {@code _} may stand as an element, spelled {@code [T?]};
 * an element is never left out, so an array has no {@code optional}.
 *
 * <p>{@code ordered} says whether element order is part of the value; output keeps the order written either way
 * ([TSON-SCHEMA] §7.5).
 */
@Typename(name = "array")
public record ArrayBody(@Field("element_type") TypeRef elementType, boolean voidable, boolean ordered,
                         @Field("unique_items") boolean uniqueItems,
                         @Field("min_items") Optional<BigInteger> minItems,
                         @Field("max_items") Optional<BigInteger> maxItems) implements Product {

    /**
     * The unconstrained shape every built-in array uses before size refinement: not voidable, ordered, non-unique,
     * unbounded.
     */
    public static ArrayBody of(TypeRef elementType) {
        return new ArrayBody(elementType, false, true, false, Optional.empty(), Optional.empty());
    }

    /**
     * {@inheritDoc} <p>The {@code min_items}/{@code max_items} pair: a container whose floor sits above its
     * ceiling admits no value of any length ([TSON-SCHEMA] §5.3). Judged by the same comparison the atom
     * families' plain inclusive bounds use, since it is the same shape.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkOrdered(violations, "min_items", minItems, "max_items", maxItems);
        return violations;
    }
}
