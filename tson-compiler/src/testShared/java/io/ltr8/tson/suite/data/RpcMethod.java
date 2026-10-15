package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;

/**
 * The host class for the corpus's {@code rpc_method} record, {@code method & { idempotent?: boolean }}: a strict
 * subtype of {@code method}, so a value of it fills a slot typed {@code method} through its supertypes.
 */
public record RpcMethod(TypeRef request, TypeRef response, List<TypeRef> errors,
                        Map<String, TypeRef> replies, Boolean idempotent) {

    /** This method's names, at the slot {@code at} reaches from the enclosing body. */
    List<Data.Named> names(String... at) {
        return Method.names(request, errors, replies, at);
    }
}
