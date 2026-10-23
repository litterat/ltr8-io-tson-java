package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The meta-kernel's {@code map} constructor's own vocabulary, resolved (Part 2 §4.2, §8.1) --
 * {@code access_pattern}/{@code size_type} are fixed ({@code NAMED}/{@code VARIABLE}) and never
 * appear in output. {@code @Field} renames each component to the kernel's own snake_case wire
 * name -- {@code ltr8-bind} otherwise writes the bare Java component name verbatim (camelCase).
 *
 * <p><b>A namespace type is read as one.</b> A type IS-A the kernel's {@code namespace} -- the kernel's {@code schema}
 * and {@code field_map}, a meta layer's {@code api => namespace & { key_type: = identifier  value_type: = method }}
 * -- resolves to a {@link RecordBody} carrying the facets it states, and a value of it is a map from {@code
 * key_type} to {@code value_type}, never a record of those facets. {@link #ofNamespace} is that map. An instance of
 * it standing as an entry ({@code myapi => !api { ... }}) is a {@link Namespace}: the map, and the constructor it is
 * an instance of.
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

    /** The kernel's constructor whose instances are maps, and the name a namespace type's chain reaches. */
    public static final String NAMESPACE = "namespace";

    /**
     * The fields a namespace type has: the facets of the map a value of it is, and nothing else, since that map is
     * the whole value and a field beside them has nowhere to be written.
     */
    public static final List<String> NAMESPACE_FACETS = List.of("key_type", "value_type", "ordered");

    /**
     * Whether {@code definition} is a namespace type: a record whose chain reaches the kernel's {@code namespace},
     * judged by where that name is defined ({@code kernel}), never by its spelling. The kernel's {@code namespace}
     * is not one of its own: it is the abstract constructor, and its chain reaches {@code top} alone.
     *
     * @param kernel whether a chain's name denotes the meta-kernel's own entry of that name
     */
    public static boolean isNamespace(Top definition, Predicate<String> kernel) {
        return definition instanceof RecordBody record && record.supertypes().contains(NAMESPACE)
                && kernel.test(NAMESPACE);
    }

    /**
     * The map a value of the namespace type {@code namespace} is written and read as: keys of its {@code key_type},
     * values of its {@code value_type}, ordered as its {@code ordered} says and unordered where nothing states it,
     * values never void and no size bounds. {@code value_type} is pinned ({@code value_type: = T}); {@code key_type}
     * is pinned, or stated by default ({@code key_type?: ~ F}), when the family the keys are names of is the one
     * the value's host supplies -- a record's {@code field_map} keyed by the record's {@code name_type} -- and
     * {@code F} stands where the host states none. Empty where either is not stated: a facet left open describes
     * no map, so the abstract {@code namespace} itself, and a namespace type that leaves either facet to a
     * subtype, has none.
     */
    public static Optional<Namespace> ofNamespace(RecordBody namespace) {
        RecordField key = namespace.fields().get("key_type");
        Optional<String> keyFamily = key == null || key.role() == FieldRole.FREE ? Optional.empty()
                : key.value().map(Token::text);
        Optional<String> value = pinned(namespace, "value_type");
        if (keyFamily.isEmpty() || value.isEmpty()) {
            return Optional.empty();
        }
        RecordField ordered = namespace.fields().get("ordered");
        boolean isOrdered = ordered != null && ordered.value().map(token -> token.text().equals("true")).orElse(false);
        return Optional.of(new Namespace(new MapBody(TypeRef.of(keyFamily.get()), TypeRef.of(value.get()), false,
                isOrdered, Optional.empty(), Optional.empty()), key.role() == FieldRole.FIXED));
    }

    private static Optional<String> pinned(RecordBody namespace, String facet) {
        RecordField field = namespace.fields().get(facet);
        return field == null || field.role() != FieldRole.FIXED ? Optional.empty()
                : field.value().map(Token::text);
    }

    /**
     * A namespace type's map ({@link #ofNamespace}), and whether its key family is pinned. A pinned family is the
     * one every key is read and judged under. A default one stands where the host of the value states none, and
     * the host supplies it otherwise, so a key is read by its spelling alone ({@link #read}) and judged under the
     * host's family once that is resolved -- for a record's {@code field_map}, where the record's field names are
     * judged by its {@code name_type}.
     *
     * @param map        keys of the stated family, values of the pinned {@code value_type}
     * @param keysPinned whether {@code key_type} is fixed rather than a default
     */
    public record Namespace(MapBody map, boolean keysPinned) {

        /** What a key of a host-supplied family is read as: a token, its spelling, judged later by the host. */
        public static final String HOST_KEYS = "text";

        /**
         * The map a value is read by: {@link #map} where the key family is pinned, and otherwise the same map with
         * its keys read as {@value #HOST_KEYS}, whose reader takes any spelling.
         */
        public MapBody read() {
            return keysPinned ? map : new MapBody(map.chain(), TypeRef.of(HOST_KEYS), map.valueType(),
                    map.voidable(), map.ordered(), map.minItems(), map.maxItems());
        }
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
