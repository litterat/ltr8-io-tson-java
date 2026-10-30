package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotated;
import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A value of a namespace type -- an instance of the kernel's {@code namespace} constructor ([TSON-SCHEMA] §4.1,
 * {@link NamespaceBody}) -- written and read as the map its facets describe. The kernel declares two, and every
 * resolved schema holds values of both: the schema map is a value of {@code schema} ({@code TsonSchema.entries}),
 * and a record's fields are a value of {@code field_map} ({@link RecordBody#fields}). A schema declares its own,
 * {@code api => !namespace { key_type: identifier  value_type: method }}, and the entry {@code myapi => !api {
 * get_orders => { ... } }} in a schema importing it holds a third, as a {@link ValueEntry}.
 *
 * <p>{@link #type} names the namespace type the value is a value of, which fixes the family its keys are names of
 * and the type of its values; it is the tag resolved output writes where the value stands as a schema entry. At a
 * named position, {@code fields: { ... }}, the position's type says which namespace type it is, and the value writes
 * as the map alone. {@link #entries} keeps each key's annotations and the order the keys were written in.
 *
 * <p>The read API of the map it holds is the value's own ({@link #get}, {@link #keySet}, {@link #getAnnotations},
 * ...), so a consumer reads a namespace as it would its map. It is not a {@link Map}: a binder or writer testing
 * {@code instanceof Map} would take it for a bare map and lose the namespace type.
 *
 * <p><b>Equal namespaces are one constructor's values with the same keys, values and key annotations.</b> Key order
 * is not compared: whether order is part of the value is a facet of the constructor ({@code ordered}), so an owner
 * whose namespace is ordered compares it in order itself, as {@link RecordBody} does its {@code field_map}.
 *
 * <p><b>A qualified name enters a {@code Namespace} and nothing else.</b> A segment after the first selects a key
 * of a namespace value (and a field of a record value); a map that is no namespace is not entered, whatever its
 * key type.
 *
 * @param type    the namespace type this is a value of
 * @param entries each key, with its annotations, mapped to its value, in the order written; immutable
 * @param <V>     the host class a value of the constructor's {@code value_type} binds to
 */
public record Namespace<V>(String type, AnnotatedMap<String, V> entries) {

    public Namespace {
        Objects.requireNonNull(type, "type");
        entries = AnnotatedMap.copyOf(entries);
    }

    /** A value of {@code type} holding {@code entries}, the key annotations of an {@link AnnotatedMap} kept. */
    public static <V> Namespace<V> of(String type, Map<String, ? extends V> entries) {
        @SuppressWarnings("unchecked")
        Map<String, V> held = (Map<String, V>) entries;
        return new Namespace<>(type, AnnotatedMap.copyOf(held));
    }

    /** The value at {@code key}, {@code null} where this namespace has no such key. */
    public V get(Object key) {
        return entries.get(key);
    }

    /** The value at {@code key}, {@code fallback} where this namespace has no such key. */
    public V getOrDefault(Object key, V fallback) {
        return entries.getOrDefault(key, fallback);
    }

    public boolean containsKey(Object key) {
        return entries.containsKey(key);
    }

    /** The keys, in the order written. */
    public Set<String> keySet() {
        return entries.keySet();
    }

    /** The values, in the order their keys were written. */
    public Collection<V> values() {
        return entries.values();
    }

    /** Each key with its value, in the order written. */
    public Set<Map.Entry<String, V>> entrySet() {
        return entries.entrySet();
    }

    /** Each key boxed with its annotations, with its value, in the order written. */
    public Set<Map.Entry<Annotated<String>, V>> annotatedEntrySet() {
        return entries.annotatedEntrySet();
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Each key and its value, in the order written. */
    public void forEach(BiConsumer<? super String, ? super V> action) {
        entries.forEach(action);
    }

    /** The annotations written at {@code key}'s own position, empty where it carries none. */
    public Annotations getAnnotations(Object key) {
        return entries.getAnnotations(key);
    }

    /** Whether any key carries annotations. */
    public boolean hasAnnotations() {
        return entries.hasAnnotations();
    }

    /** Every key that carries annotations, in the order written. */
    public Set<String> annotatedKeys() {
        return entries.annotatedKeys();
    }

    /** This value with {@code key}'s annotations replaced, everything else unchanged. */
    public Namespace<V> withAnnotations(String key, Annotations annotations) {
        return new Namespace<>(type, entries.withAnnotations(key, annotations));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Namespace<?> other) || !type.equals(other.type) || !entries.equals(other.entries)) {
            return false;
        }
        for (String key : entries.keySet()) {
            if (!entries.getAnnotations(key).equals(other.entries.getAnnotations(key))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, entries);
    }
}
