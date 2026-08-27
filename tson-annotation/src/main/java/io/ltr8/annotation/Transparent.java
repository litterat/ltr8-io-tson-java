/*
 * Copyright (c) 2020, Litterat Pty Ltd. All Rights Reserved.
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
 * A single-component wrapper that is <b>framing rather than shape</b>: the class exists in Java to give one
 * value a name and a place in a type hierarchy, and contributes nothing of its own to the wire form.
 *
 * <p><b>One rule, both directions.</b> At a position whose declared type is the annotated class, the wire
 * form is its component's: writing unwraps to the component, reading reads the component and wraps it back.
 * A consumer of the format never sees that the Java side has a wrapper at all.
 *
 * <p><b>It is a bridge, not a new kind of descriptor.</b> {@code tson-bind} resolves the annotated class to
 * the <em>component's</em> descriptor with a {@code DataClassBridge} attached -- the same arrangement {@link
 * ToData} produces, so every reader and writer that already unwraps a bridge gets this with no case of its
 * own. Which is the point: transparency is a statement about representation, and representation is what a
 * bridge is for.
 *
 * <p><b>Requirements, all checked when the descriptor is built</b> (a violation is a
 * {@code DataBindException} naming what is wrong, never a silently untransparent class):
 * <ul>
 * <li>a Java {@code record} with <b>exactly one</b> component -- the component is the wire form, and a
 *     second one would have nowhere to go;</li>
 * <li>the component's own descriptor must be a record or an atom. Those are the two that carry a bridge;
 *     an array-, map-, tuple- or union-typed component is refused rather than quietly losing its bridge.</li>
 * </ul>
 *
 * <p><b>Transparency is not inferred from having one component</b>, and must not be: plenty of single-field
 * records mean their field, and are wrong to unwrap. It is declared here or it does not hold.
 *
 * <p><b>A transparent class in a union writes no tag of its own</b>, so it cannot be selected by tag on the
 * way back in -- a reader picks it only where a position declares it. That is a real limit, and the reason
 * it is stated here rather than discovered: a transparent union member does not round-trip through the
 * union.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Transparent {
}
