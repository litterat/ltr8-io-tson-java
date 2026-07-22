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

import io.ltr8.annotation.DataBridge;
import io.ltr8.bind.analysis.DefaultClassBinder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

public class DataBindContext {

	// Resolved class information
	private final ConcurrentHashMap<Type, DataClass> descriptors = new ConcurrentHashMap<>();

	// default resolver
	private final DefaultClassBinder dataClassResolver;

	public static class Builder {

		boolean allowAny = false;

		boolean allowSerializable = false;

		public Builder() {
		}

		public Builder allowAny() {
			allowAny = true;
			return this;
		}

		public Builder allowSerializable() {
			allowSerializable = true;
			return this;
		}

		public DataBindContext build() {
			return new DataBindContext(this);
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	private DataBindContext(Builder builder) {

		this.dataClassResolver = new DefaultClassBinder();

		try {
			registerAtom(Boolean.class);
			registerAtom(boolean.class);
			registerAtom(Character.class);
			registerAtom(char.class);
			registerAtom(Byte.class);
			registerAtom(byte.class);
			registerAtom(Short.class);
			registerAtom(short.class);
			registerAtom(Integer.class);
			registerAtom(int.class);
			registerAtom(Long.class);
			registerAtom(long.class);
			registerAtom(Float.class);
			registerAtom(float.class);
			registerAtom(Double.class);
			registerAtom(double.class);
			registerAtom(BigInteger.class);
			registerAtom(BigDecimal.class);
			registerAtom(Void.class);
			registerAtom(String.class);
			registerAtom(Date.class);

		} catch (DataBindException e) {
			throw new IllegalArgumentException();
		}

	}

	public DataClass getDescriptor(Class<?> targetClass) throws DataBindException {
		// Use the erased type if type parameters not provided.
		return getDescriptor(targetClass, targetClass);
	}

	public DataClass getDescriptor(Class<?> targetClass, Type parameterizedType) throws DataBindException {

		DataClass descriptor = descriptors.get(parameterizedType);
		if (descriptor == null) {
			descriptor = dataClassResolver.resolve(this, targetClass, parameterizedType);
			if (descriptor == null) {
				throw new DataBindException(
						String.format("Unable to find suitable data descriptor for class: %s", targetClass.getName()));
			}
			register(parameterizedType, descriptor);
		}

		return descriptor;
	}

	private <T> void checkExists(Type targetClass) throws DataBindException {
		if (descriptors.containsKey(targetClass)) {
			throw new DataBindException(String.format("Class already registered: %s", targetClass.getTypeName()));
		}
	}

	private <T> void register(Type targetClass, DataClass descriptor) throws DataBindException {
		checkExists(targetClass);

		descriptors.put(targetClass, descriptor);
	}


	public void registerAtom(Class<?> targetClass) throws DataBindException {
		register(targetClass, new DataClassAtom(targetClass));
	}

	public DataClassAtom registerAtom(Class<?> targetClass, DataBridge<?, ?> bridge) throws DataBindException {
		checkExists(targetClass);

		Class<?> bridgeClass = bridge.getClass();

		try {
			Method toDataMethod = bridgeClass.getMethod("toData", targetClass);
			Method toObjectMethod = bridgeClass.getMethod("toObject", toDataMethod.getReturnType());

			MethodHandle toData = MethodHandles.publicLookup().unreflect(toDataMethod).bindTo(bridge);
			MethodHandle toObject = MethodHandles.publicLookup().unreflect(toObjectMethod).bindTo(bridge);

			DataClassBridge dataClassBridge = new DataClassBridge(toDataMethod.getReturnType(), toData, toObject);
			DataClassAtom dataClass = new DataClassAtom(targetClass,dataClassBridge);
			register(targetClass, dataClass);
			return dataClass;
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | DataBindException e) {
			throw new DataBindException("Failed to register atom bridge", e);
		}

	}

}
