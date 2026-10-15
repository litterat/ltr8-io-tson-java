package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

/**
 * The host class for the corpus's {@code binding} constructor: an HTTP binding naming the method it serves by a
 * qualified name -- {@code orders.methods.place_order}, held as a {@link TypeRef} like every reference -- with its
 * verb and route.
 */
@Typename(name = "binding")
public record Binding(TypeRef method, String verb, String route) implements Data {

    @Override
    public List<Named> names() {
        return List.of(Named.at(method, "method"));
    }
}
