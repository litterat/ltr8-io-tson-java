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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles on a public member of a described class, reached as the JVM reaches it. The JVM resolves a public member
 * of a class that is not public through a public subclass, but unreflecting resolves it against its declaring class
 * alone, so a member a super class that is not public declares is looked up through a class below it.
 *
 * <p>{@link ImmutableFinder} reaches a member <em>declarer first</em>: unreflected, then looked up through each class
 * below its declarer, nearest the declarer first. {@link GetSetFinder} reaches one <em>described first</em>: looked up
 * through the described class, then unreflected. The two differ in what they refuse and how, and each keeps its own.
 */
final class Members {

	private static final Lookup LOOKUP = MethodHandles.publicLookup();

	private Members() {
	}

	/**
	 * @throws IllegalAccessException where no class from the declarer down reaches the field: the unreflected
	 *         lookup's own refusal
	 * @throws CodeAnalysisException where, from a class it is looked up through, the field's name reaches another
	 *         field first
	 */
	static MethodHandle getterDeclarerFirst(Class<?> described, Field field)
			throws IllegalAccessException, NoSuchFieldException, CodeAnalysisException {
		try {
			return LOOKUP.unreflectGetter(field);
		} catch (IllegalAccessException unreachable) {
			for (Class<?> through : subclassesDownTo(described, field)) {
				Field reached = resolveField(through, field.getName());
				Class<?> resolved = reached == null ? null : reached.getDeclaringClass();
				if (resolved != field.getDeclaringClass()) {
					// Resolved from any class further down, the name reaches this other field first too.
					throw new CodeAnalysisException(String.format("%s is bound through field %s.%s, which a field "
							+ "of %s hides from the public lookup", described.getName(),
							field.getDeclaringClass().getName(), field.getName(), resolved.getName()));
				}
				try {
					return LOOKUP.findGetter(through, field.getName(), field.getType());
				} catch (IllegalAccessException e) {
					// Not public either: try the class below it.
				}
			}
			throw unreachable;
		}
	}

	/** @throws IllegalAccessException as for {@link #getterDeclarerFirst} */
	static MethodHandle virtualDeclarerFirst(Class<?> described, Method method)
			throws IllegalAccessException, NoSuchMethodException {
		try {
			return LOOKUP.unreflect(method);
		} catch (IllegalAccessException unreachable) {
			for (Class<?> through : subclassesDownTo(described, method)) {
				try {
					return LOOKUP.findVirtual(through, method.getName(),
							MethodType.methodType(method.getReturnType(), method.getParameterTypes()));
				} catch (IllegalAccessException e) {
					// Not public either: try the class below it.
				}
			}
			throw unreachable;
		}
	}

	record FieldHandles(MethodHandle read, MethodHandle write) {
	}

	/**
	 * A field is reached through the described class only where that class resolves its name and type to it and not
	 * to one hiding it; otherwise it is unreflected. Read and write are reached together, through one class or the
	 * other.
	 */
	static FieldHandles fieldDescribedFirst(Class<?> described, Field field) throws CodeAnalysisException {
		try {
			try {
				MethodHandle read = LOOKUP.findGetter(described, field.getName(), field.getType());
				if (MethodHandles.reflectAs(Field.class, read).equals(field)) {
					return new FieldHandles(read, LOOKUP.findSetter(described, field.getName(), field.getType()));
				}
			} catch (NoSuchFieldException | IllegalAccessException e) {
				// Reached from its own class, or not at all, where the refusal names the field.
			}
			return new FieldHandles(LOOKUP.unreflectGetter(field), LOOKUP.unreflectSetter(field));
		} catch (IllegalAccessException e) {
			throw notAccessible(described, e);
		}
	}

	/**
	 * Reached as {@link #fieldDescribedFirst} is, and with no check of what the described class resolves: a call
	 * dispatches to the same override whichever declaration it names.
	 */
	static MethodHandle virtualDescribedFirst(Class<?> described, Method method) throws CodeAnalysisException {
		try {
			try {
				return LOOKUP.findVirtual(described, method.getName(),
						MethodType.methodType(method.getReturnType(), method.getParameterTypes()));
			} catch (NoSuchMethodException | IllegalAccessException e) {
				// Reached from its own class, or not at all, where the refusal names the method.
			}
			return LOOKUP.unreflect(method);
		} catch (IllegalAccessException e) {
			throw notAccessible(described, e);
		}
	}

	static CodeAnalysisException notAccessible(Class<?> described, IllegalAccessException e) {
		return new CodeAnalysisException(String.format("%s is bound through a member that is not accessible: %s",
				described.getName(), e.getMessage()), e);
	}

	/** @return the field of this name {@code from} or its nearest super class declares, or null where none does */
	static Field resolveField(Class<?> from, String name) {
		for (Class<?> declarer = from; declarer != null; declarer = declarer.getSuperclass()) {
			try {
				return declarer.getDeclaredField(name);
			} catch (NoSuchFieldException e) {
				// Inherited: declared further up.
			}
		}
		return null;
	}

	/** @return the classes below {@code member}'s declarer down to {@code described}, nearest the declarer first */
	private static List<Class<?>> subclassesDownTo(Class<?> described, Member member) {
		List<Class<?>> through = new ArrayList<>();
		for (Class<?> c = described; c != member.getDeclaringClass(); c = c.getSuperclass()) {
			through.addFirst(c);
		}
		return through;
	}
}
