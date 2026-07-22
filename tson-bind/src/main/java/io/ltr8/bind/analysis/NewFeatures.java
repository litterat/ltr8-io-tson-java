/*
 * Copyright (c) 2021, Litterat Pty Ltd. All Rights Reserved.
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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * 
 * This uses MethodHandles reflection to see if new Java features are available to be checked. In
 * particular, we're looking for Records and Sealed Interfaces/Classes.
 *
 */
public class NewFeatures {

	MethodHandle isRecord;
	MethodHandle isSealed;
	MethodHandle sealedMembers;

	public NewFeatures() {
		isRecord = getBooleanMethod("isRecord");
		isSealed = getBooleanMethod("isSealed");
		sealedMembers = getPermittedMethod();
	}

	private MethodHandle getBooleanMethod(String methodName) {
		MethodHandle method;

		try {
			method = MethodHandles.lookup().findVirtual(Class.class, methodName, MethodType.methodType(boolean.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			method = MethodHandles.constant(boolean.class, false);

			method = MethodHandles.dropArguments(method, 0, Class.class);
		}

		return method;
	}

	private MethodHandle getPermittedMethod() {
		MethodHandle method;

		try {
			method = MethodHandles.lookup().findVirtual(Class.class, "getPermittedSubclasses",
					MethodType.methodType(Class[].class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			method = MethodHandles.constant(Class[].class, new Class[0]);
			method = MethodHandles.dropArguments(method, 0, Class.class);
		}

		return method;
	}

	public boolean isRecord(Class<?> clss) {
		try {
			return (boolean) isRecord.invoke(clss);
		} catch (Throwable e) {
			e.printStackTrace();
			return false;
		}
	}

	public boolean isSealed(Class<?> clss) {
		try {
			return (boolean) isSealed.invoke(clss);
		} catch (Throwable e) {
			e.printStackTrace();
			return false;
		}
	}

	public Class<?>[] getPermittedSubclasses(Class<?> clss) {
		try {
			return (Class<?>[]) sealedMembers.invoke(clss);
		} catch (Throwable e) {
			e.printStackTrace();
			return new Class<?>[0];
		}
	}
}
