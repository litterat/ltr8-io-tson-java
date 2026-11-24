package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.atom.AtomCoherence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The kernel's {@code namespace} constructor's own vocabulary, resolved ([TSON-SCHEMA] §4.1, §5.7, §8.1): a map type
 * keyed by names. {@code meta.namespace} composes {@code meta.map}, IS-A it, whose {@code key_type}
 * narrows to {@code meta.identifier_ref} and whose {@code voidable} is pinned false. A namespace type is an
 * instance, as a map type is one of {@code map} -- the kernel's {@code schema}, and a schema's own {@code api =>
 * !meta.namespace { key_type: meta.identifier  value_type: method }} -- and a value of it is the map its facets
 * describe ({@link #read()}), delivered as a {@link Namespace} where the position asks for one. {@code
 * access_pattern}/{@code size_type} are fixed ({@code NAMED}/{@code VARIABLE}), {@code voidable} is fixed false, and
 * none of them appears in output.
 *
 * <p><b>{@code key_type} names the family every key is read and judged under</b>, an identifier family, and is
 * always stated. {@code ordered} and the {@code min_items}/{@code max_items} bounds are map's, and mean what a map's
 * do: whether key order is part of a value (output keeps the order written either way, [TSON-SCHEMA] §7.5), and how
 * many entries a value holds.
 *
 * @param keyType   the identifier family the keys are names of
 * @param valueType the type of every value
 * @param ordered   whether key order is part of a value
 * @param minItems  the fewest entries a value holds, where bounded
 * @param maxItems  the most entries a value holds, where bounded
 */
@Typename(name = "meta.namespace")
public record NamespaceBody(TypeChain chain, @Field("key_type") TypeRef keyType,
                            @Field("value_type") TypeExpr valueType, @Field("ordered") boolean ordered,
                            @Field("min_items") Optional<Long> minItems,
                            @Field("max_items") Optional<Long> maxItems) implements Product {

    /** The kernel's constructor whose instances are namespace types. */
    public static final String NAMESPACE = "meta.namespace";

    /** The chain normalised: absent and empty are one list, and absent bounds are none. */
    @Record
    public NamespaceBody {
        chain = TypeChain.orEmpty(chain);
        Objects.requireNonNull(keyType, "keyType");
        Objects.requireNonNull(valueType, "valueType");
        minItems = minItems == null ? Optional.empty() : minItems;
        maxItems = maxItems == null ? Optional.empty() : maxItems;
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public NamespaceBody(TypeRef keyType, TypeExpr valueType, boolean ordered, Optional<Long> minItems,
                         Optional<Long> maxItems) {
        this(TypeChain.EMPTY, keyType, valueType, ordered, minItems, maxItems);
    }

    /** An unbounded namespace type in no chain. */
    public static NamespaceBody of(TypeRef keyType, TypeExpr valueType, boolean ordered) {
        return new NamespaceBody(keyType, valueType, ordered, Optional.empty(), Optional.empty());
    }

    @Override
    public NamespaceBody withChain(TypeChain chain) {
        return new NamespaceBody(chain, keyType, valueType, ordered, minItems, maxItems);
    }

    /**
     * The map a value is read by: keys of {@code key_type}, values of {@code value_type}, never void, with this
     * type's order and size bounds.
     */
    public MapBody read() {
        return new MapBody(chain, keyType, valueType, false, ordered, minItems, maxItems);
    }

    /** {@inheritDoc} <p>The {@code min_items}/{@code max_items} pair, judged as a map's is ({@link MapBody}). */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkOrdered(violations, "min_items", minItems, "max_items", maxItems);
        return violations;
    }
}
