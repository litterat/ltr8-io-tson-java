package io.ltr8.tson.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * A consumer's vocabulary record with an optional list of type names ({@code delivers?: [meta.type_ref]?}): the
 * binder hands an omitted field to the constructor as {@code null}, and nothing here has to normalise it, since the
 * class declares none of its references -- the read records each name with its slot.
 */
@Typename(name = "webhook")
public record Webhook(String path, List<TypeRef> delivers) {
}
