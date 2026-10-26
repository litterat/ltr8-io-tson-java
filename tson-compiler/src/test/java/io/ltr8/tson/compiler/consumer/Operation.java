package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A consumer's own meta-layer record: an HTTP operation, which describes an endpoint rather than the shape of a
 * data value, held as a value of a namespace type ({@code operations => namespace & { ... value_type: = operation }}).
 *
 * <p><b>The name binder is what binds it to the schema-side {@code operation} record</b>: this package is on the
 * {@code DataNameBinder}'s search path, so {@code operation} resolves here (see {@code
 * MetaLayerVocabularyTest#CONSUMER_NAMES}). {@link Typename} states the name it binds under where the binder's
 * convention would not find it; here it agrees with the convention.
 *
 * <p>{@code request}/{@code response} are {@link TypeRef}s at slots typed {@code type_kind}: the read records each
 * name with its slot and the linker judges it a member, so a name that resolves to nothing is an author error.
 */
@Typename(name = "operation")
public record Operation(String path, String method, TypeRef request, TypeRef response) {
}
