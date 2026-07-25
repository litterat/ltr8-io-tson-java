package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The {@link TsonParserFactory} for meta-kernel's {@code choice} constructor (§5.4) -- dispatches
 * by the value's own {@code !typeName} annotation against {@link ChoiceBody#variants}' fixed,
 * explicitly-declared list. The closed-union counterpart to {@link VariantParser}'s open-ended one
 * (discovered subtypes rather than an explicit list) -- once each has its own candidate-name set,
 * the actual dispatch is identical between the two, factored into {@link NamedDispatchParser}; see
 * {@link VariantParser}'s own Javadoc for why resolution stays lazy (per-branch, at read time) here
 * too, not eager for every declared variant.
 */
final class ChoiceParser {

    private ChoiceParser() {
    }

    static final TsonParserFactory FACTORY = (name, definition, ctx) -> {
        ChoiceBody body = (ChoiceBody) definition.body();
        if (body.variants().isEmpty()) {
            throw new IllegalStateException("'" + name + "' declares no variants -- nothing compilable here");
        }
        Set<String> variantNames = new LinkedHashSet<>();
        for (TypeRef variant : body.variants()) {
            variantNames.add(variant.name());
        }
        return new NamedDispatchParser(name,
                "is a choice -- a value at this position requires an explicit type annotation (!typeName) "
                        + "naming one of its declared variants to disambiguate",
                "declared variant", variantNames, ctx);
    };
}
