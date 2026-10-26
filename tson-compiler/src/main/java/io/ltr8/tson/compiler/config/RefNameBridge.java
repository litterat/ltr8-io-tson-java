package io.ltr8.tson.compiler.config;

import io.ltr8.annotation.DataBridge;
import io.ltr8.tson.schema.meta.Ref;

/**
 * {@link Ref} as an atom host: its data form is the qualified name it holds. The read that creates one at a
 * reference-typed slot does not come through here ({@code BindTargets} sets a name reader in place), so this serves
 * writing, where a delivered or undelivered reference is its name either way.
 */
public final class RefNameBridge implements DataBridge<String, Ref<?>> {

    @Override
    public String toData(Ref<?> reference) {
        return reference.name();
    }

    @Override
    public Ref<?> toObject(String name) {
        return Ref.to(name);
    }
}
