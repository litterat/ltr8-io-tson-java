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
 * <p><b>Untagged structural recovery (§5.4).</b> Where the choice is disjoint and every variant's {@link
 * DiscriminationClass} is scalar, the tag is omissible: an untagged value is recovered to the variant of its
 * own §4 base-type class. This factory precomputes that {@code class -> variant} map (reading each variant's
 * definition from the enclosing schema, via {@link ValueReaderContext#schema}) and hands it to {@link
 * NamedDispatchReader}. The derived {@code disjoint} fact already <em>is</em> class-distinctness -- {@code
 * ChoiceDisjointness} classifies through the same {@link DiscriminationClass#of} -- so a same-class pair
 * (two numbers, two strings) is never disjoint and always keeps the tag, no matter how separated its value
 * sets ({@code (email | uri)}); see {@code SPEC-FEEDBACK.md} #47. A {@code BRACE}/{@code BRACKET} variant
 * still keeps the tag here: recovery dispatches on a scalar token's resolved class, and structural recovery
 * from a container's opening delimiter isn't attempted yet.
 */
final class ChoiceReader {

    private ChoiceReader() {
    }

    /** Tree mode: a dispatched value's own annotations are captured and re-attached to the node built for it. */
    static final ValueReaderFactory CAPTURING_FACTORY = factory(true);

    /** Object-binding mode: a bound Java value has nowhere to carry annotations, so they are checked and dropped. */
    static final ValueReaderFactory FACTORY = factory(false);

    private static ValueReaderFactory factory(boolean captureAnnotations) {
        return (name, typeDefinition, context) -> {
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
            // NamedDispatchReader's positionName is message-only, so the display name goes straight in:
            // a choice lifted from `(a | b)` sugar has an internal name nobody wrote.
            return new NamedDispatchReader(EntryDisplayName.of(name, typeDefinition),
                    "is a choice -- a value at this position requires an explicit type annotation (!typeName) "
                            + "naming one of its declared variants to disambiguate",
                    "declared variant", variantNames, context.readers(),
                    untaggedRecovery(typeDefinition, body, context.schema()),
                    captureAnnotations ? AnnotationTypes.of(context) : AnnotationTypes.of(context).discarding());
        };
    }

    /**
     * {@code discrimination class -> variant name} for untagged recovery, or empty (tag stays required)
     * unless the choice is disjoint and every variant's class is scalar. The distinctness check is kept
     * although a derived {@code disjoint: true} already implies it -- a hand-assembled definition can state
     * {@code true} over variants the classes contradict, and the safe reading of that disagreement is no
     * recovery.
     */
    private static Map<DiscriminationClass, String> untaggedRecovery(TypeDefinition def, ChoiceBody body,
            TsonSchema schema) {
        if (!def.disjoint().equals(Optional.of(true))) {
            return Map.of();
        }
        Map<DiscriminationClass, String> byClass = new EnumMap<>(DiscriminationClass.class);
        for (TypeRef variant : body.variants()) {
            Optional<DiscriminationClass> variantClass = DiscriminationClass.of(variant.name(), schema.entries());
            if (variantClass.isEmpty() || !variantClass.get().scalar()
                    || byClass.putIfAbsent(variantClass.get(), variant.name()) != null) {
                return Map.of(); // a classless or container variant, or two variants sharing a class -> keep the tag
            }
        }
        return byClass;
    }
}
