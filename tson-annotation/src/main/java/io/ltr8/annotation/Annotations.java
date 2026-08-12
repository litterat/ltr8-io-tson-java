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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The TSON wire-format annotations attached to a bound value, and a record's opt-in to receiving them.
 *
 * <p><b>Declaring a component of this type is the whole opt-in.</b> A record with one gets the annotations
 * on the value it was bound from; the component is not bound from an authored field of the same name and
 * takes no part in field matching. Nothing else is required — no marker annotation, no registration:
 *
 * <pre>{@code
 * public record Person(Annotations annotations, String name) {}
 * }</pre>
 *
 * The declared type is the signal because it is the one thing the binding engine can check on its own. A
 * marker plus a separately-agreed carrier type needs two declarations to stay in step, and leaves whichever
 * layer sees only one of them unable to verify anything.
 *
 * <p>At most one component per record may be a carrier; a second is an analysis error.
 *
 * <p><b>Scope: the annotations on the value this record itself corresponds to.</b> Not those on its field
 * values, its elements, or its map keys — a component's own declared type is the only place metadata can
 * live, and a {@code String} field has none. Wrapping such a position in a box that carries both is the
 * general answer, rather than a carrier convention per container kind.
 *
 * <p>Ordered, and a name may repeat: §3.1 permits any number of occurrences of one name on a single value
 * and preserves them in source order, so {@link #values()} is a list and {@link #getAll(String)} exists.
 */
public interface Annotations {

    /** Every annotation on this value, in source order. */
    List<Annotation> values();

    /** The first annotation named {@code name}, in source order -- absent if there is none. */
    default Optional<Annotation> get(String name) {
        return values().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    /** Every annotation named {@code name}, in source order. */
    default List<Annotation> getAll(String name) {
        return values().stream().filter(a -> a.name().equals(name)).toList();
    }

    /**
     * The value of the first annotation named {@code name}, as {@code type} -- the common shape by a wide
     * margin, since most annotations carry one scalar. Empty when there is no such annotation, and equally
     * when there is one but it is the valueless form ({@link #has} is the query for those).
     *
     * @throws ClassCastException if the annotation is present with a value that is not a {@code type}
     */
    default <T> Optional<T> value(String name, Class<T> type) {
        return get(name).flatMap(a -> a.valueAs(type));
    }

    /**
     * Every value under {@code name}, as {@code type}, in source order -- §3.1 permits a name to repeat.
     * Valueless occurrences contribute nothing.
     *
     * @throws ClassCastException if any occurrence has a value that is not a {@code type}
     */
    default <T> List<T> values(String name, Class<T> type) {
        return getAll(name).stream().flatMap(a -> a.valueAs(type).stream()).toList();
    }

    /** Whether any annotation named {@code name} is present -- the common test for a valueless marker. */
    default boolean has(String name) {
        return values().stream().anyMatch(a -> a.name().equals(name));
    }

    default boolean isEmpty() {
        return values().isEmpty();
    }

    /** An immutable snapshot of {@code values}. */
    static Annotations of(List<Annotation> values) {
        return values.isEmpty() ? empty() : new Immutable(List.copyOf(values));
    }

    /** No annotations -- what a carrier receives when the value carried none. */
    static Annotations empty() {
        return Immutable.EMPTY;
    }

    /**
     * The implementation {@link #of} returns. A record rather than a lambda over {@link #values()} so that
     * equality is structural: a bound object's {@code equals} would otherwise depend on identity for this
     * component alone, which breaks the hand-built-expected-value comparison the resolver tests are written
     * in.
     */
    record Immutable(List<Annotation> values) implements Annotations {

        private static final Annotations EMPTY = new Immutable(List.of());

        public Immutable {
            values = List.copyOf(values);
        }
    }

    /** Collects annotations in source order, then freezes -- what a reader builds as it consumes them. */
    final class Builder {

        private final List<Annotation> values = new ArrayList<>();

        public Builder add(Annotation annotation) {
            values.add(annotation);
            return this;
        }

        public Annotations build() {
            return of(values);
        }
    }
}
