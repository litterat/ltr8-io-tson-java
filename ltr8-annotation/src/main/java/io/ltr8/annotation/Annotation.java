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

import java.util.Optional;

/**
 * One TSON wire-format annotation ({@code @name} or {@code @name:value}) attached to a bound value.
 *
 * <p>An empty {@code value} is the valueless form. It is not the same as an annotation carrying an absent
 * value: the format treats bare {@code @T} as shorthand for {@code @T:_}, but that expansion belongs to the
 * layer that validates against the annotation's declared type, not here.
 *
 * <p><b>{@code value} is {@code Object} because its Java form depends on how the document was read.</b>
 * Where the annotation's name resolves to a type in the governing schema, it is that type's own bound
 * object; where it resolves to nothing, it is a structural node preserving what the reader could not
 * interpret. This module names neither type and should not — it is the one place the binding engine, the
 * resolved-schema value model and consumer code can all reach, which is exactly why it stays free of both.
 */
public record Annotation(String name, Optional<Object> value) {

    public Annotation {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("an annotation name is required");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must be present or empty, never null");
        }
    }

    /**
     * This annotation's value as {@code type}, or empty when it has none -- the valueless form {@code @name},
     * where presence alone is the information.
     *
     * <p><b>A present value of the wrong type throws</b> rather than reading as absent, because the two mean
     * very different things to a caller and only one of them is a data condition. The usual cause is a
     * mismatch of read mode rather than a bad witness: with no governing schema there is no declared type to
     * bind an annotation's value through, so it is kept structurally and every typed lookup against it would
     * otherwise silently yield nothing.
     *
     * @throws ClassCastException if a value is present and is not a {@code type}
     */
    public <T> Optional<T> valueAs(Class<T> type) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Object held = value.get();
        if (!type.isInstance(held)) {
            throw new ClassCastException("annotation '@" + name + "' holds a " + held.getClass().getName()
                    + ", not a " + type.getName() + " -- an annotation's value takes the Java form the read "
                    + "produced, and a read with no governing schema binds none of them");
        }
        return Optional.of(type.cast(held));
    }

    /** The valueless form, {@code @name}. */
    public static Annotation of(String name) {
        return new Annotation(name, Optional.empty());
    }

    /** The valued form, {@code @name:value}. */
    public static Annotation of(String name, Object value) {
        return new Annotation(name, Optional.of(value));
    }
}
