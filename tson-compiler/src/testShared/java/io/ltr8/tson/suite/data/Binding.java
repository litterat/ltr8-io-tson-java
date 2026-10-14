package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.QualifiedName;

import java.util.List;

/**
 * The host class for the corpus's {@code binding} constructor: an HTTP binding naming the method it serves by
 * {@link QualifiedName} -- {@code orders.methods.place_order} -- with its verb and route.
 */
@Typename(name = "binding")
public record Binding(QualifiedName method, String verb, String route) implements Data {

    @Override
    public List<Named> names() {
        return List.of(Named.at(method, "method"));
    }
}
