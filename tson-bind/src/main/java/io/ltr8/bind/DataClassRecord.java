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

import java.lang.invoke.MethodHandle;
import java.util.Arrays;

/**
 * A DataClassRecord provides a descriptor for record data classes projected/embedded pair for use
 * in serialization libraries.
 */
public class DataClassRecord extends DataClass {

	private final boolean isMutable;

	// Empty constructor/acquirer for data object mutable objects.
	private final MethodHandle creator;

	// Constructor for the data object.
	private final MethodHandle constructor;

	// All fields in the projected class.
	private final DataClassField[] fields;

	public DataClassRecord( Class<?> targetType, DataClassBridge bridge, boolean isMutable, MethodHandle creator, MethodHandle constructor,  DataClassField[] fields) {
		super(targetType, bridge);
		this.fields = fields;
		this.isMutable = isMutable;
		this.creator = creator;
		this.constructor = constructor;
	}

	public boolean isMutable() {
		return isMutable;
	}

	public MethodHandle creator() {
		return creator;
	}

	/**
	 * @return A MethodHandle that has the signature T constructor(Object[] values).
	 */
	public MethodHandle constructor() {
		return constructor;
	}

	/**
	 * @return The list of fields and their types returned by the embed function.
	 */
	public DataClassField[] fields() {
		return fields;
	}

	@Override
	public String toString() {
		return "DataClassRecord [ typeClass=" + typeClass().getName() + ", fields=" + Arrays.toString(fields) + "]";
	}

}
