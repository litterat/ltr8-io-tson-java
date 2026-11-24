package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueRef;


/**
 * A vocabulary record holding a type's name at a kind-typed slot ({@code request: meta.ref<record>}) and a value's
 * name at a reference-typed one ({@code method?: meta.ref<method>}), as a {@link TypeRef} and a {@link ValueRef}: the
 * class declares nothing of either, and {@code ValuesByNameTest} shows each judged by its slot.
 */
@Typename(name = "filed_in_references")
public record FiledInReferences(TypeRef request, ValueRef<Object> method) {
}
