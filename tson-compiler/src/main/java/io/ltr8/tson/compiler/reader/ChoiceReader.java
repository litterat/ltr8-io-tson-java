package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.TypeExpr;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@link ValueReaderFactory} for the kernel's {@code choice} constructor. Dispatches by the value's own
 * {@code !typeName} annotation against {@link ChoiceBody#variants}' fixed, declared list, via {@link
 * NamedDispatchReader}. Same for both {@link ValueReaderFactoryRegistry#tree} and {@link
 * ValueReaderFactoryRegistry#bind} (a single shared entry, not per mode).
 *
 * <p><b>Untagged structural recovery (§5.4).</b> Where the choice is disjoint and every variant has a {@link
 * DiscriminationClass}, each distinct, the tag is omissible: an untagged value is recovered to the variant of its own
 * class -- a scalar token's §4 base-type class, or the brace or bracket that opens it ([TSON-SCHEMA] §5.4 counts the
 * delimiter among the classes). This factory precomputes that {@code class -> variant} map (reading each variant's
 * definition from the enclosing schema, via {@link ValueReaderContext#schema}, and classifying a variant written in
 * place by its body) and hands it to {@link NamedDispatchReader}. The derived {@code disjoint} fact already
 * <em>is</em> class-distinctness -- {@code ChoiceDisjointness} classifies through the same {@link
 * DiscriminationClass#of} -- so a same-class pair (two numbers, two strings) is never disjoint and always keeps the
 * tag, no matter how separated its value sets ({@code (email | uri)}), which §5.4 forbids proving.
 *
 * <p>A variant written in place has no name a tag can give, so it is reached only by recovery; the names a message
 * lists include its written form.
 */
final class ChoiceReader {

    private ChoiceReader() {
    }

    /**
     * <b>One factory for both modes.</b> Dispatch reads the type-ref without consuming it, so the variant's
     * own reader is handed the whole data-value and does with its annotations whatever that mode does
     * everywhere else. There is nothing left here for a mode to differ about.
     */
    static final ValueReaderFactory FACTORY = (name, typeDefinition, context) -> {
        if (!(typeDefinition instanceof ChoiceBody body)) {
            throw new IllegalArgumentException("'" + name + "' is not choice-shaped: " + typeDefinition);
        }
        if (body.variants().isEmpty()) {
            throw new IllegalStateException("'" + name + "' declares no variants -- nothing compilable here");
        }
        Set<String> variantNames = new LinkedHashSet<>();
        for (TypeExpr variant : body.variants()) {
            variantNames.add(variant.text());
        }
        // NamedDispatchReader's positionName is message-only, so the display name goes straight in.
        return new NamedDispatchReader(context.displayName(name, typeDefinition),
                "is a choice -- a value at this position requires an explicit type annotation (!typeName) "
                        + "naming one of its declared variants to disambiguate",
                "declared variant", variantNames, context.readers(),
                untaggedRecovery(body, context.linked()));
    };

    /**
     * {@code discrimination class -> variant} for untagged recovery, or empty (tag stays required) unless the
     * choice is disjoint and every variant has a class of its own. The distinctness check is kept although a derived
     * {@code disjoint: true} already implies it -- a hand-assembled definition can state {@code true} over variants
     * the classes contradict, and the safe reading of that disagreement is no recovery.
     */
    private static Map<DiscriminationClass, TypeExpr> untaggedRecovery(ChoiceBody body, TsonLinkedSchema linked) {
        if (!body.disjoint().equals(Optional.of(true))) {
            return Map.of();
        }
        Map<DiscriminationClass, TypeExpr> byClass = new EnumMap<>(DiscriminationClass.class);
        for (TypeExpr variant : body.variants()) {
            Optional<DiscriminationClass> variantClass = DiscriminationClass.of(variant,
                    linked.schema().entries()::get);
            if (variantClass.isEmpty() || byClass.putIfAbsent(variantClass.get(), variant) != null) {
                return Map.of(); // a classless variant, or two variants sharing a class -> keep the tag
            }
        }
        return byClass;
    }
}
