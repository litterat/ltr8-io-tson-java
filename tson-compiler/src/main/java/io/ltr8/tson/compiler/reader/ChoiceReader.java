package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@link ValueReaderFactory} for the kernel's {@code choice} constructor. Dispatches by the value's own
 * {@code !typeName} annotation to one of {@link ChoiceBody#variants}, via {@link NamedDispatchReader}. Same for both
 * {@link ValueReaderFactoryRegistry#tree} and {@link ValueReaderFactoryRegistry#bind} (a single shared entry, not
 * per mode).
 *
 * <p><b>Which variant a tag selects ([TSON-SCHEMA] §5.4)</b> is decided here, once per choice, as a table from every
 * name a tag may carry to its variant: a variant's own name and every alias meaning the same type, then each type
 * IS-A exactly one variant. The value goes to that variant's reader, which weighs the tag as a position typed by the
 * variant does (§7.2) -- so the abstract variant itself is refused there, not here, and a subtype validates as
 * itself. A type IS-A two variants and naming neither is no entry of the table.
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
        Map<String, List<String>> selectsNone = new LinkedHashMap<>();
        Map<String, TypeExpr> selects = selection(body, context, selectsNone);
        // NamedDispatchReader's positionName is message-only, so the display name goes straight in.
        return new NamedDispatchReader(context.displayName(name, typeDefinition),
                "is a choice -- a value at this position requires an explicit type annotation (!typeName) "
                        + "naming one of its declared variants to disambiguate",
                variantNames, Map.copyOf(selects), Map.copyOf(selectsNone), context.readers(),
                untaggedRecovery(body, context.linked()));
    };

    /**
     * The table the class Javadoc describes. A name meaning a variant is entered before any subtype, so at {@code (
     * base | sub )} the tag {@code !sub} selects {@code sub}; {@code ambiguous} receives each name IS-A more than one
     * variant and meaning none, with those variants' names. A variant written in place adds nothing.
     */
    private static Map<String, TypeExpr> selection(ChoiceBody body, ValueReaderContext context,
                                                   Map<String, List<String>> ambiguous) {
        var entries = context.schema().entries();
        Map<String, TypeExpr> selects = new LinkedHashMap<>();
        List<TypeRef> named = body.variants().stream().flatMap(variant -> TypeExpr.named(variant).stream()).toList();
        for (TypeRef variant : named) {
            selects.put(variant.name(), variant);
        }
        for (TypeRef variant : named) {
            String terminal = AliasChain.terminal(variant.name(), entries::get);
            Subsumption.admitting(List.of(terminal), context.namesMeaning())
                    .forEach(written -> selects.putIfAbsent(written, variant));
        }
        Map<String, List<TypeRef>> below = new LinkedHashMap<>();
        for (TypeRef variant : named) {
            String terminal = AliasChain.terminal(variant.name(), entries::get);
            Top definition = entries.get(terminal);
            if (definition == null) {
                continue;
            }
            for (String written : Subsumption.admitting(context.subtypesOf(terminal, definition),
                    context.namesMeaning())) {
                if (!selects.containsKey(written)) {
                    below.computeIfAbsent(written, ignored -> new ArrayList<>()).add(variant);
                }
            }
        }
        below.forEach((written, variants) -> {
            if (variants.size() == 1) {
                selects.put(written, variants.getFirst());
            } else {
                ambiguous.put(written, variants.stream().map(TypeRef::name).toList());
            }
        });
        return selects;
    }

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
            // An extern variant is classified at the entry it lands on, in the namespace that holds it (§5.13).
            Optional<DiscriminationClass> variantClass = variant instanceof TypeRef reference
                    ? linked.landing(reference.name()).map(end -> DiscriminationClass.of(end.name(),
                            end.in().schema().entries()::get)).orElseGet(() -> DiscriminationClass.of(variant,
                            linked.schema().entries()::get))
                    : DiscriminationClass.of(variant, linked.schema().entries()::get);
            if (variantClass.isEmpty() || byClass.putIfAbsent(variantClass.get(), variant) != null) {
                return Map.of(); // a classless variant, or two variants sharing a class -> keep the tag
            }
        }
        return byClass;
    }
}
