package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;


/**
 * A meta-layer record holding a type's name at a kind-typed slot ({@code request: kind<record>}) and a value's name at a
 * reference-typed one ({@code method?: ref<method>}), both as {@link TypeRef}s: the class declares nothing of either,
 * and {@code ValuesByNameTest} shows each judged by its slot.
 */
@Typename(name = "filed_in_references")
public record FiledInReferences(TypeRef request, TypeRef method) {
}
