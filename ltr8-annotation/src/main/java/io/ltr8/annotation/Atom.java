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
 * Says a class is an atom, one scalar on the wire, by its way in and its way out.
 *
 * <p><b>On a public constructor or public static method</b> of the class itself, taking one value the wire carries --
 * a primitive, a box, a {@code String}, a {@code BigInteger} or a {@code BigDecimal} -- it is the way in, and a class
 * has one. <b>On a public instance method</b> taking nothing, possibly inherited, it is the way out, returning that
 * value; {@link ToData} may be the way out instead. <b>On the class</b> it says the class is an atom, which then needs
 * a way in.
 *
 * <p>An enum is an atom by its constants' names, and may carry it on its type alone. On a class bound as anything
 * else -- a record of any kind, a union, a map, a collection, a class bridged through {@link ToData} -- it is refused;
 * a record's atom form is a {@link DataBridge} registered for it. A super class's static way in builds the super
 * class, and is refused from a subclass.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.CONSTRUCTOR, ElementType.TYPE, ElementType.METHOD })
public @interface Atom {
}
