package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A {@code ref<rh_method>} slot bound to the host shape {@code design/references.md} names: a {@code TypeRef}. */
@Typename(name = "rh_ref_type_ref")
public record RhRefTypeRef(TypeRef method) {
}
