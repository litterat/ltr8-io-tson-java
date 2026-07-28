package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The {@link ValueReaderFactory} for meta-kernel's {@code choice} constructor -- this package's own
 * copy of {@code compiler.ChoiceReader}. Dispatches by the value's own {@code !typeName} annotation
 * against {@link ChoiceBody#variants}' fixed, explicitly-declared list, via {@link
 * NamedDispatchReader}. Same for both {@link ValueReaderFactoryRegistry#dom()} and {@link
 * ValueReaderFactoryRegistry#bind}, matching the {@code compiler} precedent (a single shared entry,
 * not registered per mode) -- unlike {@code record}, a choice has no "own body" of its own to fall
 * back to, so there's no equivalent of {@link VariantBindReader}'s {@code DataClassUnion}-bounded
 * treatment here (yet); every variant is still resolved purely by schema name.
 */
final class ChoiceReader {

    private ChoiceReader() {
    }

    static final ValueReaderFactory FACTORY = (name, typeDefinition, resolver) -> {
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
                "declared variant", variantNames, resolver);
    };
}
