package io.ltr8.tson.compiler.consumer;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A consumer's own meta-layer constructor body: an HTTP operation, which describes an endpoint rather than
 * the shape of a data value.
 *
 * <p><b>Two things bind it to the schema-side {@code operation} constructor</b>, and both are needed:
 * {@link Typename} naming that constructor, and this package being on the {@code DataNameBinder}'s search
 * path so {@code operation} resolves here (see {@code MetaLayerDataConstructorTest#CONSUMER_NAMES}). With
 * the first missing, the compiler cannot dispatch an entry built with it; with the second missing, the
 * constructor has no reader at all and a schema applying it is refused.
 *
 * <p>{@code request}/{@code response} are {@link TypeRef}s at slots typed {@code type_kind}: the read records each
 * name with its slot and the linker judges it a member, so a name that resolves to nothing is an author error.
 */
@Typename(name = "operation")
public record Operation(String path, String method, TypeRef request, TypeRef response) implements Data {
}
