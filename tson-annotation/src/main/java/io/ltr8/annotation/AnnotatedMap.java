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

    private AnnotatedMap(Map<K, V> values, Map<K, Annotations> annotations) {
        this.values = Collections.unmodifiableMap(values);
        this.annotations = annotations;
    }

    /** A map with nothing annotated -- what every caller building one from plain entries gets. */
    public static <K, V> AnnotatedMap<K, V> of(Map<K, V> values) {
        return new AnnotatedMap<>(new LinkedHashMap<>(values), Map.of());
    }

    /** Accumulates entries, with or without annotations, in insertion order. */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /** This map, with {@code key}'s own annotations replaced -- every entry and every other key unchanged. */
    public AnnotatedMap<K, V> withAnnotations(K key, Annotations forKey) {
        Objects.requireNonNull(key, "key");
        Map<K, Annotations> updated = new LinkedHashMap<>(annotations);
        if (forKey.isEmpty()) {
            updated.remove(key);
        } else {
            updated.put(key, forKey);
        }
        return new AnnotatedMap<>(new LinkedHashMap<>(values), updated);
    }

    /** The annotations written at {@code key}'s own position -- empty when it carries none. */
    public Annotations getAnnotations(K key) {
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

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return values.entrySet();
    }

    @Override
    public V get(Object key) {
        return values.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return values.containsKey(key);
    }

    /** Builds an {@link AnnotatedMap} entry by entry -- the only way to make one with annotated keys. */
    public static final class Builder<K, V> {

        private final Map<K, V> values = new LinkedHashMap<>();
        private final Map<K, Annotations> annotations = new LinkedHashMap<>();

        public Builder<K, V> put(K key, V value) {
            values.put(key, value);
            return this;
        }

        public Builder<K, V> put(K key, V value, Annotations forKey) {
            values.put(key, value);
            if (!forKey.isEmpty()) {
                annotations.put(key, forKey);
            }
            return this;
        }

        public AnnotatedMap<K, V> build() {
            return new AnnotatedMap<>(new LinkedHashMap<>(values), new LinkedHashMap<>(annotations));
        }
    }
}
