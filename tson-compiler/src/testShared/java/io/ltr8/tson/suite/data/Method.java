package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;

/**
 * The host class for the corpus's {@code method} record: a request record, the error records and the reply records
 * by status, each held by name, and a response type. All four are {@link TypeRef}s, one host shape for every
 * reference: {@code response} sits at a {@code type_kind} slot and names a type, the others at {@code record} slots
 * and may carry segments. Nothing here declares which is which -- the resolver's read records each by-name
 * reference with its slot.
 */
public record Method(TypeRef request, TypeRef response, List<TypeRef> errors, Map<String, TypeRef> replies) {
}
