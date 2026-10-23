package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A method in {@code RefHostShapeTest}'s meta layer: a plain record, the value type of its namespace. */
@Typename(name = "rh_method")
public record RhMethod(TypeRef request) {
}
