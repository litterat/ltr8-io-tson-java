package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@link ValueReaderFactory} for meta-kernel's {@code choice} constructor. Dispatches by the value's own
 * {@code !typeName} annotation against {@link ChoiceBody#variants}' fixed, declared list, via {@link
 * NamedDispatchReader}. Same for both {@link ValueReaderFactoryRegistry#tree} and {@link
 * ValueReaderFactoryRegistry#bind} (a single shared entry, not per mode).
 *
 * <p><b>Untagged structural recovery (§5.4).</b> Where the choice is disjoint and every variant is a scalar
 * occupying a <i>distinct</i> base-type class, the tag is omissible: an untagged value is recovered to the
 * variant of its own §4 base-type class. This factory precomputes that {@code class -> variant} map (reading
 * each variant's own definition from the enclosing schema, via {@link ValueReaderContext#schema}) and hands
 * it to {@link NamedDispatchReader}; it is empty (so the tag stays required) unless every variant classifies
 * to a distinct {@link BaseTypeClass}. Distinct base-type classes are the exact TSON-text criterion -- they
 * imply value-set disjointness <i>and</i> §4 separability -- so a same-class pair (two numbers, two strings)
 * always keeps the tag, no matter how disjoint its value sets ({@code (email | uri)}); see {@code
 * SPEC-FEEDBACK.md} #23. A non-scalar variant (record/map/array) is left absent -- structural recovery
 * beyond base-type classes isn't attempted here yet.
 */
final class ChoiceReader {

    private ChoiceReader() {
    }

    static final ValueReaderFactory FACTORY = (name, typeDefinition, context) -> {
        if (!(typeDefinition.body() instanceof ChoiceBody body)) {
            throw new IllegalArgumentException("'" + name + "' is not choice-shaped: " + typeDefinition.body());
        }
        if (body.variants().isEmpty()) {
            throw new IllegalStateException("'" + name + "' declares no variants -- nothing compilable here");
        }
        Set<String> variantNames = new LinkedHashSet<>();
        for (TypeRef variant : body.variants()) {
            variantNames.add(variant.name());
        }
        return new NamedDispatchReader(name,
                "is a choice -- a value at this position requires an explicit type annotation (!typeName) "
                        + "naming one of its declared variants to disambiguate",
                "declared variant", variantNames, context.readers(),
                untaggedRecovery(typeDefinition, body, context.schema()));
    };

    /**
     * {@code base-type class -> variant name} for untagged recovery, or empty (tag stays required) unless the
     * choice is proved disjoint and every variant is a scalar of a distinct base-type class.
     */
    private static Map<BaseTypeClass, String> untaggedRecovery(TypeDefinition def, ChoiceBody body,
            TsonSchema schema) {
        if (!def.disjoint().equals(Optional.of(true))) {
            return Map.of();
        }
        Map<BaseTypeClass, String> byClass = new EnumMap<>(BaseTypeClass.class);
        for (TypeRef variant : body.variants()) {
            Optional<BaseTypeClass> variantClass =
                    Optional.ofNullable(schema.entries().get(variant.name())).flatMap(BaseTypeClass::of);
            if (variantClass.isEmpty() || byClass.putIfAbsent(variantClass.get(), variant.name()) != null) {
                return Map.of(); // a non-scalar/ambiguous variant, or two variants sharing a class -> keep the tag
            }
        }
        return byClass;
    }
}
