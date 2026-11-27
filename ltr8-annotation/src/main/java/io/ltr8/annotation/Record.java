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
 * Binds a class that is not a Java {@code record} as a record, and names the constructor that creates it.
 *
 * <p><b>On a class</b> it says the class is data. Its components are the fields its constructor stores, its public
 * fields that are not {@code final}, and its public getter and setter pairs. A Java {@code record} is data without
 * it.
 *
 * <p><b>On a constructor</b> it says the same of the class, and picks that constructor where the class has more
 * than one public constructor serving no {@link Profile}. A class with one such constructor needs none marked.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.CONSTRUCTOR, ElementType.TYPE })
public @interface Record {
}
