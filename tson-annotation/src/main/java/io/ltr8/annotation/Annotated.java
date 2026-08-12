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

import java.util.Objects;

/**
 * A value together with the TSON wire annotations (§3.1) written at the position it came from -- the opt-in
 * for positions that cannot hold an {@link Annotations} component of their own.
 *
 * <p>{@link Annotations} works by being a component of a record, which serves the value a whole record was
 * bound from and nothing else. §3.1 attaches annotations to *any* value position -- a scalar field, an array
 * element, a tuple position, either side of a map entry -- and a bound {@code String} has nowhere to put
 * one. This moves the metadata into the position's own declared type instead:
 *
 * <pre>{@code
 * public record Person(Annotated<String> name, List<Annotated<Order>> orders) {}
 * }</pre>
 *
 * <p><b>One type serves every position</b>, because what changes is the position's type rather than the kind
 * of container around it -- so there is no per-container convention to invent, and nesting composes:
 * {@code Annotated<List<Annotated<String>>>} annotates both the list and its elements. Declaring it costs
 * nothing where it is not used; a position typed {@code String} still binds to a plain {@code String}.
 *
 * <p><b>Equality and hash proxy to {@code value}</b>, deliberately. Annotations are metadata, and metadata
 * does not change what a value *is* -- the same rule that keeps them (and source positions) out of {@code
 * TypeDefinition}'s equality. That is what makes this usable in a key position: a {@code
 * Map<Annotated<String>, V>} still finds an entry by its plain name, and two entries differing only in
 * documentation remain equal. It also means a box and its bare value are *not* equal, since {@code equals}
 * is necessarily symmetric -- unwrap with {@link #value()} to compare across the boundary.
 *
 * <p>Not {@link Comparable}: {@code T} is unbounded, so there is nothing to delegate to. A caller ordering
 * boxed values uses {@code Comparator.comparing(Annotated::value)}, which reads better than an unchecked
 * cast would anyway.
 */
public record Annotated<T>(T value, Annotations annotations) {

    public Annotated {
        annotations = annotations == null ? Annotations.empty() : annotations;
    }

    /** A value carrying no annotations -- what a position gets when none were written at it. */
    public static <T> Annotated<T> of(T value) {
        return new Annotated<>(value, Annotations.empty());
    }

    public static <T> Annotated<T> of(T value, Annotations annotations) {
        return new Annotated<>(value, annotations);
    }

    /** Proxies to {@code value} -- see this class's own Javadoc for why metadata is excluded. */
    @Override
    public boolean equals(Object o) {
        return o instanceof Annotated<?> other && Objects.equals(value, other.value);
    }

    /** Proxies to {@code value}, so a boxed key hashes to the same bucket as the value it wraps. */
    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    /** The value's own {@code toString}, with any annotations named ahead of it, in §7.4's order. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (Annotation annotation : annotations.values()) {
            text.append('@').append(annotation.name());
            annotation.value().ifPresent(v -> text.append(':').append(v));
            text.append(' ');
        }
        return text.append(value).toString();
    }
}
