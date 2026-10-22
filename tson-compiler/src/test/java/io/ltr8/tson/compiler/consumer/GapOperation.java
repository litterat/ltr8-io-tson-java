package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/** {@code KindSlotGapsTest}'s DATA body: a kind-typed {@code request} and a list of records holding another. */
@Typename(name = "gap_operation")
public record GapOperation(TypeRef request, List<GapReply> replies) implements Data {

    public GapOperation {
        replies = replies == null ? List.of() : List.copyOf(replies);
    }
}
