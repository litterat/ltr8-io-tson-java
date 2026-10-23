package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.AnnotatedMap;

import java.util.Objects;

/**
 * A value of a namespace type -- an instance of a constructor IS-A the kernel's {@code namespace} ([TSON-SCHEMA]
 * §4.1), written and read as the map its facets describe: {@code myapi => !api { get_orders => { ... } }} in a
 * schema governed by a meta layer declaring {@code api => namespace & { key_type: = identifier  value_type: =
 * method }}.
 *
 * <p>{@link #type} names the namespace constructor the value is an instance of, which fixes the family its keys are
 * names of and the type of its values ({@code TsonLinkedSchema.namespace}); it is the tag resolved output writes,
 * and the entry's constructor, since a value of this class carries no {@code @Typename}. {@link #entries} keeps
 * each key's annotations and the order the keys were written in.
 *
 * <p><b>A qualified name enters a {@code Namespace} and nothing else.</b> A segment after the first selects a key
 * of a namespace value (and a field of a record value); a map that is no namespace is not entered, whatever its
 * key type. The schema map ({@code schema}) and a record's fields ({@code field_map}) are the kernel's two namespace
 * types, so they are values of this shape too.
 *
 * <p>An entry whose value is a {@code Namespace} is no type: nothing is typed by it, and a slot typed by a kind
 * holds it only where the kind's {@code of} is the namespace constructor or one it IS-A.
 *
 * @param type    the namespace constructor this value is an instance of
 * @param entries each key, with its annotations, mapped to its value, in the order written
 * @param <V>     the host class a value of the constructor's {@code value_type} binds to
 */
public record Namespace<V>(String type, AnnotatedMap<String, V> entries) implements Top {

    public Namespace {
        Objects.requireNonNull(type, "type");
        entries = AnnotatedMap.copyOf(entries);
    }
}
