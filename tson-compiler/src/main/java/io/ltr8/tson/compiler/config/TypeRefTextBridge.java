package io.ltr8.tson.compiler.config;

import io.ltr8.annotation.DataBridge;
import io.ltr8.tson.atom.ReferenceGrammar;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * {@link TypeRef} as an atom host: its data form is the reference's text, parsed by {@link ReferenceGrammar} and
 * written as {@link TypeRef#text} ([TSON-DATA] §7.4, SPEC-FEEDBACK.md #12) -- the text a reader rolls a
 * reference's tree into in TSON, where it is written unquoted, and what a JSON string holds. A reference-typed slot
 * of a schema read does not come through here ({@code BindTargets} sets {@code KindSlotReader} in place, which
 * records the name for the linker), and the writers write a reference themselves; this serves a reference bound
 * from its text anywhere else, a JSON string's among them.
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
