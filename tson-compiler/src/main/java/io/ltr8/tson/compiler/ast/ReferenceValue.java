package io.ltr8.tson.compiler.ast;

import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A {@code reference} core-value ([TSON-DATA] §7.4, SPEC-FEEDBACK.md #12): a qualified name with its argument list,
 * {@code pair<uuid B>}, as the stream assembled it. A qualified name without arguments is a {@link TokenValue}.
 * Uninterpreted like a token: what the head and its arguments name is a reader's or the resolver's to judge, and
 * {@code reference} holds each argument as the grammar classified it, a qualified name a reference and any other
 * token a value with its token form.
 */
public record ReferenceValue(TypeRef reference) implements CoreValue {
}
