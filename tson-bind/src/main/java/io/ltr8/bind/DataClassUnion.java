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

import java.util.Arrays;
import java.util.Objects;

/**
 * 
 * A Union data class is tagged union type. It can be represented in a number of ways in Java:
 * 
 * <ul>
 * <li>interface: An interface allows multiple data representations as child classes.
 * <li>abstract class: Similar to an interface, an abstract or base class can have multiple data
 * representations.
 * <li>embedded union: A class with one or more fields where only one is present at any one time.
 * </ul>
 *
 * The members are the actual classes instead of a DataClass. This is to ensure that infinite
 * resolution loops do not occur.
 */
public class DataClassUnion extends DataClass {

	private Class<?>[] memberTypes;

	private final boolean isSealed;

	public DataClassUnion(Class<?> targetType, Class<?>[] members, boolean isSealed) {
		super( targetType);

		this.memberTypes = Objects.requireNonNull(members);
		this.isSealed = isSealed;
	}

	public Class<?>[] memberTypes() {
		return memberTypes;
	}

	public boolean isSealed() {
		return isSealed;
	}

	public boolean isMemberType(Class<?> dataClass) {
		boolean found = false;

		// Get a reference to latest version as this method isn't synchronized.
		// Probably a better way to do this.
		Class<?>[] types = this.memberTypes;
		for (Class<?> dClass : types) {
			if (dClass.equals(dataClass)) {
				found = true;
				break;
			}
		}

		return found;
	}

	/**
	 * As different implementations of an interface or abstract class will get loaded at different times
	 * the list of union types will not all be known at startup. Therefore, it needs to be possible to
	 * add additional implementations to the list. One of the reasons why sealed classes are a better
	 * choice.
	 * 
	 * @throws DataBindException when the union is sealed and new members can't be added.
	 */
	public synchronized void addMemberType(Class<?> newType) throws DataBindException {

		if (isMemberType(newType)) {
			return;
		}

		if (isSealed) {
			throw new DataBindException("Union type is sealed. No addition member types can be added.");
		}

		Class<?>[] newMemberTypes = Arrays.copyOf(memberTypes, memberTypes.length + 1);
		newMemberTypes[newMemberTypes.length - 1] = newType;

		this.memberTypes = newMemberTypes;
	}

	public Object checkIsMember(Object value) throws DataBindException {

		if (value == null) {
			return value;
		}

		Class<?>[] values = this.memberTypes;
		for (Class<?> dataClass : values) {
			if (dataClass == value.getClass()) {
				return value;
			}
		}

		throw new DataBindException(String.format("Value '%s' not in valid types %s", value.getClass().getName(),
				membersToString(memberTypes)));
	}

	@Override
	public String toString() {
		return "DataClassUnion [typeClass=" + typeClass().getName() + ", memberTypes=" + membersToString(memberTypes)
				+ ", isSealed=" + isSealed + "]";
	}

	private String membersToString(Class<?>[] dataClass) {

		if (dataClass == null || dataClass.length == 0) {
			return "[]";
		}

		StringBuilder b = new StringBuilder();
		b.append('[');
		for (int x = 0; x < dataClass.length; x++) {
			b.append(String.valueOf(dataClass[x].getName()));
			if (x == dataClass.length - 1) {
				break;
			}
			b.append(", ");
		}
		b.append(']');
		return b.toString();
	}

}
