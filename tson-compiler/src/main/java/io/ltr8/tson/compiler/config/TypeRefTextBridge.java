package io.ltr8.tson.compiler.config;

import io.ltr8.annotation.DataBridge;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * {@link TypeRef} as an atom host: its data form is the reference's text, parsed by {@link ReferenceGrammar} and
 * written as {@link TypeRef#text} (SPEC-FEEDBACK.md #12). A reference-typed slot of a schema read does not come
 * through here ({@code BindTargets} sets {@code KindSlotReader} in place, which records the name for the linker);
 * this serves an annotation's value ({@code @application}) and writing.
 */
public final class TypeRefTextBridge implements DataBridge<String, TypeRef> {

    /** The bridge; it holds nothing. */
    public TypeRefTextBridge() {
    }

    @Override
    public String toData(TypeRef reference) {
        return reference.text();
    }

    @Override
    public TypeRef toObject(String text) {
        return ReferenceGrammar.parse(text);
    }
}
