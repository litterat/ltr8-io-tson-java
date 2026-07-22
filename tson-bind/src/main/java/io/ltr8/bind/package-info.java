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
package io.ltr8.bind;
/**
 * 
 * @formatter:off
 * 
 * The Litterat bind library provides a way of categorising classes into one of the following:
 * 
 * atom - Primitive or accepts single argument to construct.
 * data - Has multiple values and tagged as data class.
 * class with data - a class that produces a data class.
 * array - 
 * class - A class that doesn't fit the previous three throw an exception.
 * 
 * It then provides the meta data and conversion functions for each category.
 * This can form the basis for different serialization and data structure
 * conversions. The mapper package provides examples of converting an Object to Object[]
 * and back as well as the same for Object to Map<String,Object>
 * 
 * @formatter:on
 */
