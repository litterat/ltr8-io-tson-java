/*
 * Copyright (c) 2020-2021, Litterat Pty Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ltr8.bind.analysis;

import io.ltr8.annotation.Annotated;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Union;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.ParameterizedType;

/**
 * Placeholder to collect information about field details. Used as a temporary hold during the
 * resolving process.
 */
public class ComponentInfo {

	private String name;

	private final Class<?> type;

	private MethodHandle writeMethod;

	private MethodHandle readMethod;

	private int constructorArgument;

	private ParameterizedType paramType;

	private Field field;

	private Union union;

	private boolean annotated;

	public ComponentInfo(String name, Class<?> type) {
		this.name = name;
		this.type = type;
		this.constructorArgument = -1;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public void setField(Field field) throws CodeAnalysisException {
		if (this.field != null && !this.field.equals(field))
			throw new CodeAnalysisException(String.format("Field '%s' already has Field annotation", this.name));
		this.field = field;
	}

	public Field getField() {
		return this.field;
	}

	public void setUnion(Union union) throws CodeAnalysisException {
		if (this.union != null && unionsEqual(union, this.union))
			throw new CodeAnalysisException(String.format("Field '%s' already has Field annotation", this.name));
		this.union = union;
	}

	private boolean unionsEqual(Union u1, Union u2) {
		if (u1 == u2 || (u1 == null && u2 == null)) {
			return true;
		}

		if (u1 == null || u2 == null) {
			return false;
		}

		if (u1.value() == u2.value() && u1.sealed() == u2.sealed()) {
			return true;
		}

		return false;
	}

	public Union getUnion() {
		return this.union;
	}

	public void setAnnotated(boolean annotated) {
		this.annotated = annotated;
	}

	public boolean isAnnotated() {
		return this.annotated;
	}

	public Class<?> getType() {
		return type;
	}

	public MethodHandle getWriteMethod() {
		return writeMethod;
	}

	public void setWriteMethod(MethodHandle writeMethod) {
		this.writeMethod = writeMethod;
	}

	public MethodHandle getReadMethod() {
		return readMethod;
	}

	public void setReadMethod(MethodHandle readMethod) {
		this.readMethod = readMethod;
	}

	public int getConstructorArgument() {
		return constructorArgument;
	}

	public void setConstructorArgument(int constructorArgument) {
		this.constructorArgument = constructorArgument;
	}

	public ParameterizedType getParamType() {
		return paramType;
	}

	public void setParamType(ParameterizedType paramType) {
		this.paramType = paramType;
	}

}
