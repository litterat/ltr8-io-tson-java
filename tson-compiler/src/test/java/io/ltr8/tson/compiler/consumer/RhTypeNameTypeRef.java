package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A {@code meta.ref<record>} slot bound to a {@code TypeRef}. */
@Typename(name = "rh_type_name_type_ref")
public record RhTypeNameTypeRef(TypeRef request) {
}
