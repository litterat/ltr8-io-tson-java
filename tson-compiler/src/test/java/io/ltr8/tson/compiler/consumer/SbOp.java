package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** The family's one subtype, declaring the kind-typed slot its base lacks. */
@Typename(name = "sb_op")
public record SbOp(TypeRef request) implements SbEndpoint {
}
