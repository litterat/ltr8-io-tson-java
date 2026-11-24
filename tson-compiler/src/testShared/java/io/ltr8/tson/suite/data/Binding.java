package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.ValueRef;

/**
 * The host class for the corpus's {@code binding} record, a value of its {@code bindings} namespace type: an HTTP
 * binding naming the method it serves at a slot typed {@code meta.ref<method>}, by a qualified name --
 * {@code orders.place_order}, held as a {@link ValueRef} that receives the {@link Method} when the schema links -- with its
 * verb and route.
 */
public record Binding(ValueRef<Method> method, String verb, String route) {
}
