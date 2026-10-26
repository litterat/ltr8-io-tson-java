package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A {@code kind<record>} slot bound to a {@code TypeRef}. */
@Typename(name = "rh_kind_type_ref")
public record RhKindTypeRef(TypeRef request) {
}
