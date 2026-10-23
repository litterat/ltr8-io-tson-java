package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/** {@code KindSlotGapsTest}'s namespace value: a kind-typed {@code request} and a list of records holding another. */
@Typename(name = "gap_operation")
public record GapOperation(TypeRef request, List<GapReply> replies) {

    public GapOperation {
        replies = replies == null ? List.of() : List.copyOf(replies);
    }
}
