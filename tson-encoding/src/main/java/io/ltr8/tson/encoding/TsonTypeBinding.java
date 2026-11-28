package io.ltr8.tson.encoding;

import io.ltr8.bind.DataClass;
import io.ltr8.tson.schema.EntryKey;
import io.ltr8.tson.schema.meta.Top;

import java.util.Objects;

/**
 * One entry of a linked schema and the host class it binds to: the entry ({@code key}, in the schema declaring it),
 * its {@code definition}, the class's description ({@code dataClass}), and the binding candidate the binder bound
 * ({@code boundAs}) -- the entry itself, an alias naming it, or the constructor it is composed over
 * ({@code TsonLinkedSchema.bindingCandidates}).
 */
public record TsonTypeBinding(EntryKey key, Top definition, DataClass dataClass, EntryKey boundAs) {

    public TsonTypeBinding {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(dataClass, "dataClass");
        Objects.requireNonNull(boundAs, "boundAs");
    }
}
