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
package io.ltr8.bind.mapper;

import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;

/** The refusals both mappers make, so that each refuses the same value in the same words. */
final class Checks {

	private Checks() {
	}

	/** Thrown alike for a read with no value for a required field and a write of a record holding none. */
	static DataBindException missingField(DataClassRecord record, DataClassField field) {
		return new DataBindException(String.format("%s is missing its required field '%s'",
				record.typeClass().getName(), field.name()));
	}

	/**
	 * The class a union value's data names, loaded without being initialised and refused before anything describes
	 * it unless it can be a member: one {@code union} lists, or, where the union admits additions, a class of its
	 * type. Data supplies the name, and must not be able to run a class's static initialiser, or have it described,
	 * by naming it. A sealed union admits nothing it does not list ({@link DataClassUnion#isMemberType}), so a class
	 * of its type that it does not list is refused here too.
	 *
	 * @throws ClassNotFoundException where the name resolves to no class
	 */
	static Class<?> candidate(DataClassUnion union, String name) throws DataBindException, ClassNotFoundException {
		Class<?> candidate = Class.forName(name, false, Checks.class.getClassLoader());
		for (Class<?> member : union.memberTypes()) {
			if (member == candidate) {
				return candidate;
			}
		}
		if (!union.isSealed() && union.typeClass().isAssignableFrom(candidate)) {
			return candidate;
		}
		throw new DataBindException(String.format("'%s' is not a member of union %s", name,
				union.typeClass().getName()));
	}

	/**
	 * A tuple's values line up with its elements by position, and an element of a primitive type cannot take the
	 * null an absent value is.
	 */
	static Object[] tuple(DataClassTuple tuple, Object[] values) throws DataBindException {
		DataClassElement[] elements = tuple.elements();
		if (values.length != elements.length) {
			throw new DataBindException(String.format("%s has %d elements but the array has length %d",
					tuple.typeClass().getName(), elements.length, values.length));
		}
		for (int x = 0; x < elements.length; x++) {
			if (values[x] == null && elements[x].dataClass().typeClass().isPrimitive()) {
				throw new DataBindException(String.format("%s is missing its required element %d",
						tuple.typeClass().getName(), x));
			}
		}
		return values;
	}
}
