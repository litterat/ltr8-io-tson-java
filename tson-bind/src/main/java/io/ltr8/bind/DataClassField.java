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
package io.ltr8.bind;

import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * 
 * This is analogous to the Java reflection RecordComponent of a Record class but for Data classes.
 * It provides an isPresent method handle, to check if a value is present/non-null. If a value is
 * present the accessor MethodHandle can be called to return the value. The accessor returns the
 * value as primitive or class object. If a method to set the value is present a MethodHandle for
 * setter is available.
 * <p>
 * The field wraps Optional, OptionalInt, OptionalLong, and OptionalDouble so that isPresent will
 * proxy the call through to the Optional object. Calling the accessor will proxy the call to the
 * Optional.get call.
 * <p>
 * If the dataClass type is a Record or Atom then toData and toObject methodhandles should be
 * called. Here's an example from the MapMapper.
 * 
 * <pre>
 * DataClassField field = fields[fieldIndex];
 * 
 * if (field.isPresent(data)) {
 * 	DataClass fieldDataClass = field.dataClass();
 * 	Object fv = toMap(fieldDataClass, field.get(data));
 * 	map.put(field.name(), fv);
 * }
 * </pre>
 */
public class DataClassField {

	// constructor index.
	private final int index;

	// name of the field
	private final String name;

	// type of the field
	private final Class<?> type;

	private final DataClass dataClass;

	// Is the field required to be set.
	private final boolean isRequired;

	private final MethodHandle isPresent;

	// accessor read handle. signature: type t = object.getT();
	private final MethodHandle accessor;

	// setter write handle. signature object.setT( type t);
	private final Optional<MethodHandle> setter;

	public DataClassField(int index, String name, Class<?> type,  DataClass dataClass, boolean isRequired,
			MethodHandle isPresent, MethodHandle readHandle, MethodHandle setter) {
		this.index = index;
		this.name = name;
		this.type = type;
		this.dataClass = dataClass;
		this.isRequired = isRequired;
		this.isPresent = isPresent;
		this.accessor = readHandle;
		this.setter = Optional.ofNullable(setter);
	}

	public int index() {
		return index;
	}

	public String name() {
		return name;
	}

	public Class<?> type() {
		return type;
	}

	public DataClass dataClass() {
		return dataClass;
	}

	/**
	 * A field is required automatically for primitive classes. For Nullable classes the Field
	 * annotation can be used to set the field to be required. Optional fields are by definition
	 * optional.
	 * 
	 * @return true if field is required to be set with a value.
	 */
	public boolean isRequired() {
		return isRequired;
	}

	/**
	 * Using the same naming as Optional.isPresent, returns a method handle that checks
	 * if the specific field has a non-null value. Will always return true for primitive types.
	 *
	 * @return MethodHandle that has signature, boolean isPresent(Object o);
	 */
	public MethodHandle isPresent() {
		return isPresent;
	}

	public boolean isPresent(Object record) {
		try {
			return (boolean) isPresent.invoke(record);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public MethodHandle accessor() {
		return accessor;
	}

	public Object get(Object record) {
		try {
			return accessor.invoke(record);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public Optional<MethodHandle> setter() {
		return setter;
	}

	public void set(Object record, Object value) {
		try {
			setter.get().invoke(record, value);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public String toString() {
		return "DataClassField [index=" + index + ", name=" + name + ", type=" + dataClass + ", isRequired=" + isRequired
				+ "]";
	}
}
