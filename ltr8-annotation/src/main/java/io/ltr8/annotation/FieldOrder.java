/*
 * Copyright (c) 2020-2021, Litterat Pty Ltd. All Rights Reserved.
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
 * The order of a class's components on the wire: every component named once, by its Java name and not its
 * {@link Field} name, an {@link Unbound} one included. An order that does not is refused when the class is
 * described.
 *
 * <p>Without it, the components a constructor takes come first, in parameter order, then the public fields and the
 * getter and setter pairs by wire name.
 *
 * <p>It orders a class described by its own components. On a {@link Tuple}, a {@link Transparent} class or one
 * bridged through {@link ToData} it has no effect.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE })
public @interface FieldOrder {

	String[] value();
}
