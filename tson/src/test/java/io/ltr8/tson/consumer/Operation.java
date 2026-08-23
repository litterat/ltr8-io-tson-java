package io.ltr8.tson.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * A consumer's own meta-layer constructor body: an HTTP operation, which describes an endpoint rather than
 * the shape of a data value. The front-door peer of {@code io.ltr8.tson.compiler.consumer.Operation} --
 * same three-part wiring, reached through {@link io.ltr8.tson.Tson} rather than by building a compiled
 * meta registry by hand.
 */
@Typename(name = "operation")
public record Operation(String path, String method, TypeRef request, TypeRef response) implements Data {

    @Override
    public List<TypeRef> references() {
        return List.of(request, response);
    }
}
