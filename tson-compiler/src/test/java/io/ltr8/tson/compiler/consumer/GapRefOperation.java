package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

/** {@code KindSlotGapsTest}'s DATA body whose one slot is typed {@code type_ref}, the application form. */
@Typename(name = "gap_ref_operation")
public record GapRefOperation(TypeRef request) implements Data {
}
