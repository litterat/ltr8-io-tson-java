package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A method in {@code RefHostShapeTest}'s vocabulary schema: a plain record, the value type of its namespace. */
@Typename(name = "rh_method")
public record RhMethod(TypeRef request) {
}
