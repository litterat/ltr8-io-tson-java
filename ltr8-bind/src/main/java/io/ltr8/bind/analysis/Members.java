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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles on a public member of a described class, reached as the JVM reaches it: looked up by name and type through
 * the first class from its declarer down to the described class that the public lookup can access. The JVM resolves
 * a public member of a class that is not public through a public subclass, but unreflecting resolves it against its
 * declaring class alone.
 *
 * <p>A field is refused as hidden where, from a class it would be looked up through, its name and type resolve to
 * another field: the JVM resolves them so from every class further down too. A method needs no such check, since a
 * call dispatches to the same override whichever declaration it resolves to. A constructor is not inherited and a
 * static method may be hidden by one below it, so each is reached through its own class alone. A member no such
 * class reaches is refused naming the member, with the lookup's refusal as the cause, and one that is not public is
 * refused as such. Each is looked up by name and type rather than unreflected: an unreflected member's refusal names
 * its type by identity, so differs from run to run.
 */
final class Members {

	private static final Lookup LOOKUP = MethodHandles.publicLookup();

	private Members() {
	}

	record FieldHandles(MethodHandle read, MethodHandle write) {
	}

	static MethodHandle getter(Class<?> described, Field field) throws CodeAnalysisException {
		return reach(described, field, declarerDownTo(described, field),
				through -> LOOKUP.findGetter(through, field.getName(), field.getType()));
	}

	/** Read and write are reached together, through one class. */
	static FieldHandles getterAndSetter(Class<?> described, Field field) throws CodeAnalysisException {
		return reach(described, field, declarerDownTo(described, field), through -> new FieldHandles(
				LOOKUP.findGetter(through, field.getName(), field.getType()),
				LOOKUP.findSetter(through, field.getName(), field.getType())));
	}

	static MethodHandle virtual(Class<?> described, Method method) throws CodeAnalysisException {
		return reach(described, method, declarerDownTo(described, method), through -> LOOKUP.findVirtual(through,
				method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes())));
	}

	static MethodHandle staticMethod(Class<?> described, Method method) throws CodeAnalysisException {
		return reach(described, method, List.of(method.getDeclaringClass()), through -> LOOKUP.findStatic(through,
				method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes())));
	}

	static MethodHandle constructor(Class<?> described, Constructor<?> constructor) throws CodeAnalysisException {
		Class<?> declarer = constructor.getDeclaringClass();
		try {
			return LOOKUP.findConstructor(declarer, MethodType.methodType(void.class, constructor.getParameterTypes()));
		} catch (IllegalAccessException e) {
			throw new CodeAnalysisException(String.format("%s is bound through a member that is not accessible: "
					+ "constructor %s(%s), of a class the public lookup cannot access", described.getName(),
					declarer.getName(), Arrays.stream(constructor.getParameterTypes()).map(Class::getTypeName)
							.collect(Collectors.joining(", "))), e);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(declarer.getName() + "'s constructor is not found by its own type", e);
		}
	}

	private interface Reach<T> {
		T through(Class<?> through) throws ReflectiveOperationException;
	}

	private static <T> T reach(Class<?> described, Member member, List<Class<?>> classes, Reach<T> reach)
			throws CodeAnalysisException {
		if (!Modifier.isPublic(member.getModifiers())) {
			// Refused unlooked: the lookup's refusal of a member that is not public names its class loader, by
			// identity where the loader has no name.
			throw notAccessible(described, member, "which is not public", null);
		}
		IllegalAccessException refused = null;
		for (Class<?> through : classes) {
			if (member instanceof Field field) {
				Field resolved = resolveField(through, field.getName(), field.getType());
				if (!field.equals(resolved)) {
					throw new CodeAnalysisException(String.format("%s is bound through field %s.%s, which a field "
							+ "of %s hides from the public lookup", described.getName(),
							field.getDeclaringClass().getName(), field.getName(),
							resolved.getDeclaringClass().getName()));
				}
			}
			try {
				return reach.through(through);
			} catch (IllegalAccessException e) {
				// The member is public, so the class it is resolved from is what the lookup cannot access.
				if (refused == null) {
					refused = e;
				}
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(String.format("%s.%s resolves from %s to no member",
						member.getDeclaringClass().getName(), member.getName(), through.getName()), e);
			}
		}
		throw notAccessible(described, member, "through no class the public lookup can access", refused);
	}

	private static CodeAnalysisException notAccessible(Class<?> described, Member member, String why,
			IllegalAccessException cause) {
		return new CodeAnalysisException(String.format("%s is bound through a member that is not accessible: %s %s.%s, "
				+ "%s", described.getName(), member instanceof Field ? "field" : "method",
				member.getDeclaringClass().getName(), member.getName(), why), cause);
	}

	/**
	 * Resolves a field reference as the JVM does (JVMS 5.4.3.2): a field the class declares, then one its super
	 * interfaces resolve, then one its super class resolves.
	 *
	 * @return the field, or null where none of this name and type is reached
	 */
	private static Field resolveField(Class<?> from, String name, Class<?> type) {
		for (Field field : from.getDeclaredFields()) {
			if (field.getName().equals(name) && field.getType() == type) {
				return field;
			}
		}
		for (Class<?> superInterface : from.getInterfaces()) {
			Field field = resolveField(superInterface, name, type);
			if (field != null) {
				return field;
			}
		}
		return from.getSuperclass() == null ? null : resolveField(from.getSuperclass(), name, type);
	}

	/** @return {@code member}'s declarer and the classes below it down to {@code described}, the declarer first */
	private static List<Class<?>> declarerDownTo(Class<?> described, Member member) {
		List<Class<?>> through = new ArrayList<>();
		for (Class<?> c = described; c != member.getDeclaringClass(); c = c.getSuperclass()) {
			through.addFirst(c);
		}
		through.addFirst(member.getDeclaringClass());
		return through;
	}
}
