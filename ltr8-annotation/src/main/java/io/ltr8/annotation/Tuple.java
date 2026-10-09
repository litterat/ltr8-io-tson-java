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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a genuine Java {@code record} as a tuple -- a heterogeneous, fixed-arity product bound
 * positionally (constructor argument order / {@code RecordComponent} order), not by field name.
 * Distinct from {@code @Record} (a hand-written, pre-record immutable class bound to a TSON
 * {@code record}, named-field access) even though both wrap a Java record-shaped constructor: a
 * plain Java {@code record} with no {@code @Tuple} still binds as an ordinary named-field TSON
 * {@code record} via {@code RecordComponentFinder} -- this annotation is what opts a specific
 * record class into positional, array-shaped binding instead.
 *
 * <p>Only a genuine Java {@code record} may carry this annotation for now -- not a hand-written
 * immutable class the way {@code @Record} allows. This mirrors the meta-kernel's own {@code tuple}
 * constructor ({@code ~product & { access_pattern: INDEX, size_type: FIXED, elements: [...] } }),
 * ahead of the Part 2 schema work that will eventually let a schema declare this instead of a Java
 * annotation declaring it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Tuple {
}
