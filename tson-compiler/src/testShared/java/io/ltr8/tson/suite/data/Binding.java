package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.Ref;


/**
 * The host class for the corpus's {@code binding} constructor: an HTTP binding naming the method it serves at a slot
 * typed {@code ref<method>}, by a qualified name -- {@code orders.methods.place_order}, held as a {@link Ref} that
 * receives the {@link Method} when the schema links -- with its verb and route.
 */
@Typename(name = "binding")
public record Binding(Ref<Method> method, String verb, String route) implements Data {

}
