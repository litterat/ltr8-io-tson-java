package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code map} constructor's own vocabulary, resolved (Part 2 §4.2, §8.1) --
 * {@code access_pattern}/{@code size_type} are fixed ({@code NAMED}/{@code VARIABLE}) and never
 * appear in output. Also backs every instance of the kernel's {@code namespace}, a map in every fact -- the
 * kernel's own {@code schema} and {@code field_map} among them -- whose constructor its entry's tag records.
 * {@code @Field} renames each component to the kernel's own snake_case wire
 * name -- {@code ltr8-bind} otherwise writes the bare Java component name verbatim (camelCase).
 *
 * <p>{@code voidable} governs the <b>value</b> and only the value, spelled {@code {K => V?}} -- a key can
 * never be void ([TSON-DATA] §2.9), so there is nothing else for the name to be ambiguous between. It is the
 * same fact an array element and a tuple position carry, defaulting the same way, so a map value is never void
 * unless {@code ?} says so.
 *
 * <p>{@code ordered} says whether entry order is part of the value, and is false unless a schema says so, as
 * a JSON object's is; output keeps the order written either way ([TSON-SCHEMA] §7.5).
 */
@Typename(name = "map")
public record MapBody(TypeChain chain,
        @Field("key_type") TypeRef keyType, @Field("value_type") TypeRef valueType,
                       @Field("voidable") boolean voidable, @Field("ordered") boolean ordered,
                       @Field("min_items") Optional<BigInteger> minItems,
                       @Field("max_items") Optional<BigInteger> maxItems) implements Product {
    /** The chain normalised: absent and empty are one list. */
    @Record
    public MapBody {
        chain = TypeChain.orEmpty(chain);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public MapBody(TypeRef keyType, TypeRef valueType, boolean voidable, boolean ordered, Optional<BigInteger> minItems,
            Optional<BigInteger> maxItems) {
        this(TypeChain.EMPTY, keyType, valueType, voidable, ordered, minItems, maxItems);
    }

    @Override
    public MapBody withChain(TypeChain chain) {
        return new MapBody(chain, keyType, valueType, voidable, ordered, minItems, maxItems);
    }

    /** An unconstrained map: values never void, unordered, no size bounds. */
    public static MapBody of(TypeRef keyType, TypeRef valueType) {
        return new MapBody(keyType, valueType, false, false, Optional.empty(), Optional.empty());
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
