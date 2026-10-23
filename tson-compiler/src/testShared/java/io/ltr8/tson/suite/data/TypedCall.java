package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.TypeRef;

/**
 * The host class for the corpus's {@code typed_call} record, {@code service_call & { request?: kind<record>
 * response?: type_kind }}: a request record and a response type, each a type's name held as a {@link TypeRef}.
 */
public record TypedCall(TypeRef request, TypeRef response) implements ServiceCall {
}
