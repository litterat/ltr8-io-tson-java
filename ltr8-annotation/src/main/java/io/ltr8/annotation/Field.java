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
 * States what a class alone cannot about one component: its name on the wire, that a read must supply it, or the
 * bridge its value crosses.
 *
 * <p>It goes on a member of the component: a constructor parameter, a field, an accessor or a setter. On a record
 * component it reaches that component's field, accessor and constructor parameter. Where several members carry it,
 * each attribute comes from whichever states it, two stating one differently refuse the class, and a
 * {@link Profile} constructor's parameter prevails. On a member of no component, or of a class bound as no record by
 * its own components, it refuses the class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER })
public @interface Field {

	/** The component's name on the wire; empty keeps its Java name. */
	String value() default "";

	/** Whether a read must supply the component. A primitive component is required whatever this says. */
	boolean required() default false;

	/** The bridge between the component's Java type and its data type; {@code DataBridge} itself means none. */
	Class<? extends DataBridge> bridge() default DataBridge.class;
}
