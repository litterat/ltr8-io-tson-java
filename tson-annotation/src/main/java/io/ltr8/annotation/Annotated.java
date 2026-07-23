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
 * Marks a genuine Java record component as the receiver for the *bound value's own* wire-format
 * annotations (TSON §3.1's {@code @name[:value]} metadata, e.g. {@code @doc:"..."} or
 * {@code @deprecated} on the record itself) -- an opt-in carrier, since an immutable record can't
 * be retrofitted with extra state after construction the way a mutable POJO field could.
 *
 * <p>Deliberately narrow, matching what a Java object model can actually represent: this recovers
 * only the annotations on the value the whole record corresponds to, not on its individual field
 * values (a bare {@code String} field, an array element, a map key -- none of those have anywhere
 * in Java to carry extra metadata of their own). Recovering *those* requires the parsed AST
 * directly ({@code DataValue#annotations()} on the field's own value), not this annotation --
 * there's no plan to add a per-field/per-element equivalent; see {@code SPEC-FEEDBACK.md} for why.
 *
 * <p>At most one component per record may carry this annotation, and its declared type must be
 * {@code io.ltr8.tson.mapper.TsonAnnotations}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.PARAMETER })
public @interface Annotated {
}
