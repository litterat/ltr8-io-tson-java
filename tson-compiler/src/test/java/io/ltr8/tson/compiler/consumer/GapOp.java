package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** The subtype of {@link GapEndpoint} that adds a kind-typed {@code request} the base does not declare. */
@Typename(name = "gap_op")
public record GapOp(int status, TypeRef request) implements GapEndpoint {
}
