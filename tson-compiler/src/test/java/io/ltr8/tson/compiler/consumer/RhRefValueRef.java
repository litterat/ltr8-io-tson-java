package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.ValueRef;

/** A {@code meta.ref<rh_method>} slot bound to the host shape {@code design/references.md} names: a {@code ValueRef}. */
@Typename(name = "rh_ref_value_ref")
public record RhRefValueRef(ValueRef<Object> method) {
}
