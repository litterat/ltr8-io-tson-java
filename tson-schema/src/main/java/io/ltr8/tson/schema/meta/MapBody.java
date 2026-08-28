package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code map} constructor's own vocabulary, resolved (Part 2 §4.2, §8.1) --
 * {@code access_pattern}/{@code size_type} are fixed ({@code NAMED}/{@code VARIABLE}) and never
 * appear in output. Also backs the kernel's own {@code schema} type ({@code map<type_name,
 * type_definition>}). {@code @Field} renames each component to the kernel's own snake_case wire
 * name -- {@code tson-bind} otherwise writes the bare Java component name verbatim (camelCase).
 *
 * <p>{@code state} governs the <b>value</b> and only the value, spelled {@code {K => V?}} -- a key can
 * never be absent ([TSON-DATA] §2.9), so there is no second state for the name to be ambiguous between.
 * It is the same two-member {@link ElementState} an array element and a tuple position carry, defaulting
 * the same way, so a map value defaults REQUIRED like every other container position and is loosened with
 * {@code ?} rather than being permissive by default with no way to tighten it.
 */
@Typename(name = "map")
public record MapBody(@Field("key_type") TypeRef keyType, @Field("value_type") TypeRef valueType,
                       @Field("state") ElementState state,
                       @Field("min_items") Optional<BigInteger> minItems,
                       @Field("max_items") Optional<BigInteger> maxItems) implements Product {

    /** An unconstrained map: values required, no size bounds. */
    public static MapBody of(TypeRef keyType, TypeRef valueType) {
        return new MapBody(keyType, valueType, ElementState.REQUIRED, Optional.empty(), Optional.empty());
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
