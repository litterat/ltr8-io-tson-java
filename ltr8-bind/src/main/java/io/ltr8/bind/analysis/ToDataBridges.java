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
package io.ltr8.bind.analysis;

import io.ltr8.annotation.ToData;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.analysis.ClassSignals.Signal;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.List;

/**
 * The {@link DataClassBridge} of a class bound through {@link ToData}: the record class its own clause names, the
 * public constructor taking that class, and its {@code toData()}.
 */
final class ToDataBridges {

	private static final String TODATA_METHOD = "toData";

	private ToDataBridges() {
	}

	static DataClassBridge resolveBridge(ClassSignals signals) throws DataBindException {
		Class<?> targetClass = signals.type();
		Class<?> dataType = findToDataClass(targetClass);
		if (signals.fieldOrder() != null) {
			throw new DataBindException(String.format("%s carries @FieldOrder, but is bridged through ToData to %s, "
					+ "which describes it; an order applies only to a class described by its own components, so it "
					+ "belongs on %2$s", targetClass.getName(), dataType.getName()));
		}
		Constructor<?> constructor = bridgeConstructor(signals, dataType);
		try {
			MethodHandle toObject = Members.constructor(targetClass, constructor);
			MethodHandle toData = Members.virtual(targetClass, targetClass.getMethod(TODATA_METHOD));
			return new DataClassBridge(dataType, toData, toObject);
		} catch (CodeAnalysisException e) {
			throw new DataBindException(String.format(
					"%s is not accessible through the public lookup: a bridged class is public, in a package its "
							+ "module exports", targetClass.getName()), e);
		} catch (NoSuchMethodException e) {
			// ToData declares toData() public, so every class implementing it has one.
			throw new IllegalStateException(e);
		}
	}

	/**
	 * The record type {@code targetClass} names in its own {@code implements ToData<S>}. A supertype's clause is
	 * not followed: binding through it would need a rule for a class reaching {@code ToData} by two paths, and a
	 * class that declares the clause itself says which record it is with no rule at all.
	 */
	private static Class<?> findToDataClass(Class<?> targetClass) throws DataBindException {
		for (Type declared : targetClass.getGenericInterfaces()) {
			if (declared == ToData.class) {
				throw new DataBindException(String.format(
						"%s declares ToData raw: a bridged class names its record type, so declare "
								+ "implements ToData<S> on %1$s with S its record class", targetClass.getName()));
			}
			if (declared instanceof ParameterizedType parameterized && parameterized.getRawType() == ToData.class) {
				Type recordType = parameterized.getActualTypeArguments()[0];
				if (recordType instanceof Class<?> recordClass) {
					return recordClass;
				}
				throw new DataBindException(String.format(
						"%s declares ToData<%s>, whose record type is %s, not a class: a bridged class's record "
								+ "type is a class", targetClass.getName(), recordType.getTypeName(),
						kindOf(recordType)));
			}
		}
		throw inheritedToData(targetClass);
	}

	private static DataBindException inheritedToData(Class<?> targetClass) {
		Class<?> declarer = toDataDeclarer(targetClass);
		Class<?> recordClass = declaredRecordClass(declarer);
		String recordType = recordClass != null ? recordClass.getName() : "S";
		String withS = recordClass != null ? "" : " with S its record class";
		return new DataBindException(String.format(
				"%s inherits ToData from %s: a bridged class declares ToData itself, so declare "
						+ "implements ToData<%s> on %1$s%s", targetClass.getName(), declarer.getName(), recordType,
				withS));
	}

	private static Class<?> toDataDeclarer(Class<?> type) {
		if (List.of(type.getInterfaces()).contains(ToData.class)) {
			return type;
		}
		Class<?> superclass = type.getSuperclass();
		if (superclass != null && ToData.class.isAssignableFrom(superclass)) {
			return toDataDeclarer(superclass);
		}
		for (Class<?> superinterface : type.getInterfaces()) {
			if (ToData.class.isAssignableFrom(superinterface)) {
				return toDataDeclarer(superinterface);
			}
		}
		throw new IllegalStateException(type.getName() + " reaches ToData through none of its supertypes");
	}

	/** The class {@code declarer}'s own {@code ToData} clause names, or {@code null} where it names none. */
	private static Class<?> declaredRecordClass(Class<?> declarer) {
		for (Type declared : declarer.getGenericInterfaces()) {
			if (declared instanceof ParameterizedType parameterized && parameterized.getRawType() == ToData.class
					&& parameterized.getActualTypeArguments()[0] instanceof Class<?> recordClass) {
				return recordClass;
			}
		}
		return null;
	}

	private static String kindOf(Type type) {
		return switch (type) {
			case TypeVariable<?> _ -> "a type variable";
			case ParameterizedType _ -> "a parameterized type";
			case GenericArrayType _ -> "a generic array type";
			default -> "an unrecognised type";
		};
	}

	private static Constructor<?> bridgeConstructor(ClassSignals signals, Class<?> dataType)
			throws DataBindException {
		Class<?> targetClass = signals.type();
		if (signals.has(Signal.INTERFACE)) {
			throw new DataBindException(String.format(
					"%s is an interface: a bridged class is a class with a public constructor taking its record "
							+ "type %s", targetClass.getName(), dataType.getName()));
		}
		if (signals.has(Signal.ABSTRACT)) {
			throw new DataBindException(String.format(
					"%s is abstract: a bridged class is built from its record type %s, and an abstract class "
							+ "cannot be built", targetClass.getName(), dataType.getName()));
		}
		try {
			return targetClass.getConstructor(dataType);
		} catch (NoSuchMethodException e) {
			throw new DataBindException(String.format("%s has no public constructor taking %s",
					targetClass.getName(), dataType.getName()));
		}
	}
}
