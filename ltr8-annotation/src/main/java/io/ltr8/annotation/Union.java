/*
 * Copyright (c) Litterat Pty Ltd. All Rights Reserved.
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
 * Says a value is one of several classes.
 *
 * <p><b>On an interface or an abstract class</b> it makes the type a union. With members listed it is a union of
 * those. With none it is open: each class described that implements or extends the type joins it. A Java
 * {@code sealed} type is a union of the classes it permits without this annotation.
 *
 * <p><b>On a component</b>, on its constructor parameter, field or accessor, it lists the classes that component's
 * value may be, where its declared type does not say.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
public @interface Union {

	/** The member classes. Empty, on a type, makes an open union. */
	Class<?>[] value() default {};

	/** Whether the union admits no member beyond those listed. Read only beside a list: an open union is not sealed. */
	boolean sealed() default true;
}
