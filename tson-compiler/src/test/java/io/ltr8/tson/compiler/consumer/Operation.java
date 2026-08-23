package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

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
 * <p>{@code request}/{@code response} are real {@link TypeRef}s rather than tokens, so {@link #references()}
 * hands them to the linker and a name that resolves to nothing is an author error.
 */
@Typename(name = "operation")
public record Operation(String path, String method, TypeRef request, TypeRef response) implements Data {

    @Override
    public List<TypeRef> references() {
        return List.of(request, response);
    }
}
