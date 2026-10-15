package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The host class for the corpus's {@code method} record: a request record, the error records and the reply records
 * by status, each held by name, and a response type. All four are {@link TypeRef}s, one host shape for every
 * reference: {@code response} sits at a {@code type_ref} slot and names a type, the others at {@code record} slots
 * and may carry segments. Not a {@link Data} body itself -- a method is a value inside an interface -- so it hands
 * its names to the body that holds it, at the slot given.
 */
public record Method(TypeRef request, TypeRef response, List<TypeRef> errors,
                     Map<String, TypeRef> replies) {

    /** This method's names, at the slot {@code at} reaches from the enclosing body. */
    List<Data.Named> names(String... at) {
        return names(request, errors, replies, at);
    }

    /** The names a method-shaped value holds, shared with the records that compose {@code method}. */
    static List<Data.Named> names(TypeRef request, List<TypeRef> errors,
                                  Map<String, TypeRef> replies, String... at) {
        List<Data.Named> names = new ArrayList<>();
        if (request != null) {
            names.add(Data.Named.at(request, slot(at, "request")));
        }
        if (errors != null) {
            for (int i = 0; i < errors.size(); i++) {
                names.add(Data.Named.at(errors.get(i), slot(at, "errors", Integer.toString(i))));
            }
        }
        if (replies != null) {
            replies.forEach((status, reply) -> names.add(Data.Named.at(reply, slot(at, "replies", status))));
        }
        return names;
    }

    /** All the methods of an interface-shaped body, each at {@code /<field>/<key>}. */
    static List<Data.Named> namesOf(String field, Map<String, Method> methods) {
        List<Data.Named> names = new ArrayList<>();
        if (methods != null) {
            methods.forEach((key, method) -> names.addAll(method.names(field, key)));
        }
        return names;
    }

    private static String[] slot(String[] at, String... more) {
        return Stream.concat(Stream.of(at), Stream.of(more)).toArray(String[]::new);
    }
}
