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
package io.ltr8.bind.analysis;

import io.ltr8.annotation.Field;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.*;
import java.util.*;

/**
 * Finds mutable components via public fields and getter/setter method pairs -- pure reflection,
 * no bytecode analysis involved (unlike {@link ImmutableFinder}, which needs it to recover
 * constructor-argument-to-field names). Run after {@link ImmutableFinder} for a hand-written
 * class so constructor-set (immutable) and setter-set (mutable) fields on the same class both get
 * discovered; a genuine Java record never reaches this finder at all, since it can't have setters.
 */
public class GetSetFinder implements ComponentFinder {

	@Override
	public void findComponents(Class<?> clss, Constructor<?> constructor, List<ComponentInfo> fields)
			throws CodeAnalysisException {

		List<ComponentInfo> getSetList = new ArrayList<>();
		Map<ComponentInfo, Member> members = new IdentityHashMap<>();

		Lookup lookup = MethodHandles.publicLookup();

		// A member a super class declares is typed at the arguments the described class binds.
		Map<TypeVariable<?>, Type> bindings = TypeArguments.inherited(clss);

		// A final field has no setter: one the constructor binds is already a component, read as ImmutableFinder
		// found it and written through the constructor, and one it does not bind is no component, since nothing
		// can write it.
		java.lang.reflect.Field[] classFields = clss.getFields();
		for (java.lang.reflect.Field classField : classFields) {
			int modifiers = classField.getModifiers();
			if (Modifier.isPublic(modifiers) && !Modifier.isTransient(modifiers) && !Modifier.isStatic(modifiers)
					&& !Modifier.isFinal(modifiers)) {

				Type type = TypeArguments.substitute(classField.getGenericType(), bindings);
				Class<?> erasure = type == classField.getGenericType() ? classField.getType()
						: TypeArguments.erasure(type);
				ComponentInfo info = new ComponentInfo(classField.getName(), erasure);

				Field fieldAnnotation = classField.getAnnotation(Field.class);
				if (fieldAnnotation != null) {
					// Found a matching setter/getter pair, so add to fields.
					info.setField(fieldAnnotation);
				}
				info.readUnbound(classField);

				reachField(lookup, clss, classField, info);
				MethodHandle read = info.getReadMethod();
				MethodHandle write = info.getWriteMethod();
				info.setReadMethod(read.asType(read.type().changeReturnType(erasure)));
				info.setWriteMethod(write.asType(write.type().changeParameterType(1, erasure)));

				if (type instanceof ParameterizedType parameterized) {
					info.setParamType(parameterized);
				}

				getSetList.add(info);
				members.put(info, new Member(classField.getDeclaringClass(), null, null));

			}
		}

		// https://stackoverflow.com/questions/5001172/java-reflection-getting-fields-and-methods-in-declaration-order/38929813
		// declared methods are in no particular order and it is not possible to discover it.
		// Many mention byte code analysis and line number tables, but that is asking for trouble when code
		// is compiled for production.
		//
		// A pair is a component only when both halves are public instance methods. A pair that is not is no
		// component, as a private field is not: the class is described from its other members. A getter pairs
		// only with the one-argument setter of its generic return type: another overload, or a setter taking
		// another type, even one of the same erasure, is not handed the value the getter reads.
		//
		// Either half may be declared by a super class. A getter is the most derived declaration of its name, so an
		// override is the pair's getter at its own return type, and a setter the most derived of that type. Bridges
		// are skipped: a covariant override's stands beside the method it bridges, and a visibility bridge carries
		// no generic type, which the method it forwards to, further up, does. An interface's default method is no
		// half.
		Map<String, Method> getters = new HashMap<>();
		List<Method> setters = new ArrayList<>();
		for (Class<?> declarer = clss; declarer != null; declarer = declarer.getSuperclass()) {
			for (Method method : declarer.getDeclaredMethods()) {
				String name = method.getName();
				if (method.isBridge() || name.length() <= 3) {
					continue;
				}
				if (method.getParameterCount() == 0 && name.startsWith("get")) {
					getters.putIfAbsent(name, method);
				} else if (method.getParameterCount() == 1 && name.startsWith("set")) {
					setters.add(method);
				}
			}
		}
		for (Method getter : getters.values()) {
			if (!isPublicInstance(getter)) {
				continue;
			}
			String getterName = getter.getName();
			Type type = TypeArguments.substitute(getter.getGenericReturnType(), bindings);
			Method setter = setters.stream()
					.filter(method -> method.getName().equals("s" + getterName.substring(1)) && TypeArguments
							.sameType(TypeArguments.substitute(method.getGenericParameterTypes()[0], bindings), type))
					.findFirst().orElse(null);
			if (setter == null || !isPublicInstance(setter)) {
				continue;
			}

			String fieldName = Character.toLowerCase(getterName.charAt(3)) + getterName.substring(4);
			Class<?> erasure = type == getter.getGenericReturnType() ? getter.getReturnType()
					: TypeArguments.erasure(type);
			ComponentInfo info = new ComponentInfo(fieldName, erasure);

			Field getterField = getter.getAnnotation(Field.class);
			if (getterField != null) {
				info.setField(getterField);
			}

			Field setterField = setter.getAnnotation(Field.class);
			if (setterField != null) {
				info.setField(setterField);
			}
			info.readUnbound(getter);
			info.readUnbound(setter);

			MethodHandle read = reachMethod(lookup, clss, getter);
			MethodHandle write = reachMethod(lookup, clss, setter);
			info.setReadMethod(read.asType(read.type().changeReturnType(erasure)));
			// A fluent setter returns its object, or another the old value; either is dropped.
			info.setWriteMethod(write.asType(MethodType.methodType(void.class, write.type().parameterType(0), erasure)));

			if (type instanceof ParameterizedType parameterized) {
				info.setParamType(parameterized);
			}

			getSetList.add(info);
			members.put(info, new Member(getter.getDeclaringClass(), getter, setter));
		}

		// We can find the list in different orders from Java, so sort them before
		// adding them as fields.
		Collections.sort(getSetList, new Comparator<ComponentInfo>() {

			@Override
			public int compare(ComponentInfo o1, ComponentInfo o2) {

				String o1Name = o1.getName();
				if (o1.getField() != null && !o1.getField().value().strip().equals("")) {
					o1Name = o1.getField().value();
				}

				String o2Name = o2.getName();
				if (o2.getField() != null && !o2.getField().value().strip().equals("")) {
					o2Name = o2.getField().value();
				}

				return o1Name.compareTo(o2Name);
			}
		});

		// A field or pair writes a constructor's component only where it is that component's member: of the same name,
		// and declared by the class the constructor stores it in (a pair by its getter's), or a pair whose getter the
		// constructor analysis matched as the component's accessor. A subclass's own field or pair of that name is a
		// component of its own.
		ListIterator<ComponentInfo> iter = getSetList.listIterator();
		while (iter.hasNext()) {
			ComponentInfo field = iter.next();
			Member member = members.get(field);

			ComponentInfo existingField = fields.stream().filter(e -> e.getName().equals(field.getName())
					&& (e.getDeclaringClass() == null || e.getDeclaringClass() == member.declarer()
							|| (member.getter() != null && e.isAccessor(member.getter()))))
					.findFirst().orElse(null);
			if (existingField != null) {
				iter.remove();
				existingField.setWriteMethod(field.getWriteMethod());
				if (field.isUnbound()) {
					existingField.setUnbound(true);
				}
			}
		}

		// Add any remaining.
		fields.addAll(getSetList);

		// Check any private fields to see if we need to capture Field annotations.
		// After adding getters/setters because we might pick up fields that are found using immutable
		// finder.
		java.lang.reflect.Field[] privateFields = clss.getDeclaredFields();
		for (java.lang.reflect.Field classField : privateFields) {
			int modifiers = classField.getModifiers();
			if (Modifier.isPrivate(modifiers) && !Modifier.isTransient(modifiers) && !Modifier.isStatic(modifiers)) {

				// A super class may declare a field of this name too, and this annotation is not its.
				ComponentInfo info = fields.stream().filter(e -> e.getName().equals(classField.getName())
						&& (e.getDeclaringClass() == null || e.getDeclaringClass() == clss))
						.findFirst().orElse(null);

				Field fieldAnnotation = classField.getAnnotation(Field.class);
				if (fieldAnnotation != null) {
					if (info == null) {
						throw new CodeAnalysisException(String
								.format("Could not find matching getter/setter for field '%s'", classField.getName()));
					}

					// Found a matching setter/getter pair, so add to fields.
					info.setField(fieldAnnotation);
				}

				// A private field of no component is off the wire already.
				if (info != null) {
					info.readUnbound(classField);
				}
			}
		}

		// A pair the class inherits holds a value apart from any other component of its name, which the wire would
		// carry only one of.
		for (ComponentInfo pair : getSetList) {
			Member member = members.get(pair);
			if (member.getter() == null
					|| member.getter().getDeclaringClass() == clss && member.setter().getDeclaringClass() == clss) {
				continue;
			}
			String name = wireName(pair);
			if (fields.stream().anyMatch(other -> other != pair && wireName(other).equals(name))) {
				throw new CodeAnalysisException(String.format(
						"%s has two components named '%s', one the pair %s and %s it inherits; @Field on the pair "
								+ "names one apart",
						clss.getName(), name, qualified(member.getter()), qualified(member.setter())));
			}
		}
	}

	/**
	 * The class declaring a public field or getter/setter pair (a pair's getter's), and the pair's getter and
	 * setter, or null for a field.
	 */
	private record Member(Class<?> declarer, Method getter, Method setter) {
	}

	/**
	 * A public member of a super class that is not public is reached through the described class, as the JVM
	 * resolves it; unreflected, it is checked against the class declaring it. A field is reached so only where the
	 * described class resolves its name and type to that field and not to one hiding it.
	 */
	private static void reachField(Lookup lookup, Class<?> clss, java.lang.reflect.Field field, ComponentInfo info)
			throws CodeAnalysisException {
		try {
			try {
				MethodHandle read = lookup.findGetter(clss, field.getName(), field.getType());
				if (MethodHandles.reflectAs(java.lang.reflect.Field.class, read).equals(field)) {
					info.setReadMethod(read);
					info.setWriteMethod(lookup.findSetter(clss, field.getName(), field.getType()));
					return;
				}
			} catch (NoSuchFieldException | IllegalAccessException e) {
				// Reached from its own class, or not at all, where the refusal names the field.
			}
			info.setReadMethod(lookup.unreflectGetter(field));
			info.setWriteMethod(lookup.unreflectSetter(field));
		} catch (IllegalAccessException e) {
			throw notAccessible(clss, e);
		}
	}

	/**
	 * Reached as {@link #reachField} is, and with no check of what the described class resolves: a call dispatches
	 * to the same override whichever declaration it names.
	 */
	private static MethodHandle reachMethod(Lookup lookup, Class<?> clss, Method method) throws CodeAnalysisException {
		try {
			try {
				return lookup.findVirtual(clss, method.getName(),
						MethodType.methodType(method.getReturnType(), method.getParameterTypes()));
			} catch (NoSuchMethodException | IllegalAccessException e) {
				// Reached from its own class, or not at all, where the refusal names the method.
			}
			return lookup.unreflect(method);
		} catch (IllegalAccessException e) {
			throw notAccessible(clss, e);
		}
	}

	private static CodeAnalysisException notAccessible(Class<?> clss, IllegalAccessException e) {
		return new CodeAnalysisException(String.format("%s is bound through a member that is not accessible: %s",
				clss.getName(), e.getMessage()), e);
	}

	private static String wireName(ComponentInfo component) {
		Field field = component.getField();
		return field != null && !field.value().isBlank() ? field.value() : component.getName();
	}

	private static String qualified(Method method) {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName();
	}

	private static boolean isPublicInstance(Method method) {
		int modifiers = method.getModifiers();
		return Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers);
	}

}
