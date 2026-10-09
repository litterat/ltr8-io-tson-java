/*
 * Copyright (c) 2026, Litterat Pty Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ltr8.annotation;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A map whose keys carry annotations, presented as an ordinary {@code Map<K, V>}.
 *
 * <p>§3.1 lets an annotation attach to either side of a map entry, so a key is an annotatable position like
 * any other and {@link Annotated} is what carries one. But making that visible in the key *type* --
 * {@code Map<Annotated<K>, V>} -- is unusable in practice: {@link Map#get(Object)} takes {@code Object}, so
 * every existing {@code get(plainKey)} keeps compiling and starts returning {@code null}. This presents the
 * plain key type and keeps the annotations reachable beside it:
 *
 * <pre>{@code
 * AnnotatedMap<String, TypeDefinition> entries = schema.entries();
 * TypeDefinition person = entries.get("person");                 // as before
 * Annotations about  = entries.getAnnotations("person");         // and its own metadata
 * }</pre>
 *
 * <p>Insertion-ordered, and unmodifiable once built: §3.1 preserves annotations in source order, and the
 * schema map they key is itself ordered.
 *
 * <p><b>There is deliberately no {@code Map<Annotated<K>, V>} anywhere in this API</b>, because that shape
 * cannot answer the question it appears to. {@code Map} exposes no way to recover a *stored* key from an
 * equal one -- {@code get} returns the value, and {@link Annotated} equality proxies to the value precisely
 * so a plain key finds an annotated one -- so the key object holding the annotations is never handed back.
 * Reading them means scanning {@code entrySet}, on every lookup. This type is what that shape should have
 * been: annotations indexed by key, answerable in one step, with the plain {@code Map} contract intact.
 */
public final class AnnotatedMap<K, V> extends AbstractMap<K, V> {

    private final Map<K, V> values;
    private final Map<K, Annotations> annotations;
    private final boolean immutable;

    /** An empty, mutable map -- what a binder allocates before filling it. */
    public AnnotatedMap() {
        this(new LinkedHashMap<>(), new LinkedHashMap<>(), false);
    }

    /** As {@link #AnnotatedMap()}; the capacity hint matches the shape a binder's constructor handle expects. */
    public AnnotatedMap(int capacity) {
        this(new LinkedHashMap<>(Math.max(capacity, 1)), new LinkedHashMap<>(), false);
    }

    private AnnotatedMap(Map<K, V> values, Map<K, Annotations> annotations, boolean immutable) {
        this.values = values;
        this.annotations = annotations;
        this.immutable = immutable;
    }

    /** An immutable copy -- {@code entries}' own key annotations included when it has any. */
    public static <K, V> AnnotatedMap<K, V> copyOf(Map<K, V> entries) {
        Map<K, Annotations> annotations = new LinkedHashMap<>();
        if (entries instanceof AnnotatedMap<K, V> annotated) {
            annotations.putAll(annotated.annotations);
        }
        return new AnnotatedMap<>(new LinkedHashMap<>(entries), annotations, true);
    }

    /** An immutable map with nothing annotated. */
    public static <K, V> AnnotatedMap<K, V> of(Map<K, V> values) {
        return copyOf(values);
    }

    /** This map, with {@code key}'s own annotations replaced -- a fresh immutable map, this one untouched. */
    public AnnotatedMap<K, V> withAnnotations(K key, Annotations forKey) {
        Objects.requireNonNull(key, "key");
        AnnotatedMap<K, V> copy = copyOf(this);
        if (forKey.isEmpty()) {
            copy.annotations.remove(key);
        } else {
            copy.annotations.put(key, forKey);
        }
        return copy;
    }

    /** The annotations written at {@code key}'s own position -- empty when it carries none. */
    public Annotations getAnnotations(Object key) {
        return annotations.getOrDefault(key, Annotations.empty());
    }

    /** Whether any key in this map carries annotations at all. */
    public boolean hasAnnotations() {
        return !annotations.isEmpty();
    }

    /** Every key that carries annotations, in insertion order. */
    public Set<K> annotatedKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(annotations.keySet()));
    }

    /**
     * Every entry with its key boxed together with that key's annotations -- the view a binder iterates, so
     * that an ordinary {@code Map.Entry.getKey()} yields the annotations along with the key and nothing has
     * to ask this map for them separately.
     *
     * <p>A boxed key is safe to hand out here, unlike in a {@code Map<Annotated<K>, V>}: the caller receives
     * the box and can read it. What does not work is a *map keyed* by one, because a map never returns the
     * key object it stored.
     */
    public Set<Map.Entry<Annotated<K>, V>> annotatedEntrySet() {
        Map<Annotated<K>, V> boxed = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : values.entrySet()) {
            boxed.put(new Annotated<>(entry.getKey(), getAnnotations(entry.getKey())), entry.getValue());
        }
        return Collections.unmodifiableMap(boxed).entrySet();
    }

    /**
     * Adds an entry whose key arrives boxed with its own annotations -- the fill operation a binder drives
     * this map through, the mirror of {@link #annotatedEntrySet()}.
     */
    @SuppressWarnings("unchecked")
    public V putAnnotated(Object boxedKey, V value) {
        Annotated<K> box = (Annotated<K>) boxedKey;
        return put(box.value(), value, box.annotations());
    }

    /** An entry whose key carries {@code forKey}. */
    public V put(K key, V value, Annotations forKey) {
        V previous = put(key, value);
        if (forKey != null && !forKey.isEmpty()) {
            annotations.put(key, forKey);
        }
        return previous;
    }

    @Override
    public V put(K key, V value) {
        if (immutable) {
            throw new UnsupportedOperationException("this AnnotatedMap is immutable");
        }
        return values.put(key, value);
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return immutable ? Collections.unmodifiableMap(values).entrySet() : values.entrySet();
    }

    @Override
    public V get(Object key) {
        return values.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return values.containsKey(key);
    }
}
