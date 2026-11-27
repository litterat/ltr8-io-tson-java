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
import io.ltr8.bind.analysis.ComponentInfo.Role;

import java.lang.invoke.MethodHandle;
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
	public void findComponents(Class<?> clss, Map<TypeVariable<?>, Type> bindings, Constructor<?> constructor,
			List<ComponentInfo> fields) throws CodeAnalysisException {

		List<ComponentInfo> getSetList = new ArrayList<>();
		Map<ComponentInfo, Member> members = new IdentityHashMap<>();

		// A member a super class declares is typed at the arguments the described class binds.
		Map<TypeVariable<?>, Type> inherited = TypeArguments.inherited(clss);

		// A final field has no setter: one the constructor binds is already a component, read as ImmutableFinder
		// found it and written through the constructor, and one it does not bind is no component, since nothing
		// can write it.
		java.lang.reflect.Field[] classFields = clss.getFields();
		for (java.lang.reflect.Field classField : classFields) {
			int modifiers = classField.getModifiers();
			if (Modifier.isPublic(modifiers) && !Modifier.isTransient(modifiers) && !Modifier.isStatic(modifiers)
					&& !Modifier.isFinal(modifiers)) {

				ComponentInfo info = new ComponentInfo(clss, classField.getName(),
						TypeArguments.resolve(TypeArguments.substitute(classField.getGenericType(), inherited), bindings));
				info.addMember(Role.PUBLIC_FIELD, classField);
				Class<?> erasure = info.getType();

				Members.FieldHandles handles = Members.getterAndSetter(clss, classField);
				MethodHandle read = handles.read();
				MethodHandle write = handles.write();
				info.setReadMethod(read.asType(read.type().changeReturnType(erasure)));
				info.setWriteMethod(write.asType(write.type().changeParameterType(1, erasure)));

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
			Type type = TypeArguments.substitute(getter.getGenericReturnType(), inherited);
			Method setter = setters.stream()
					.filter(method -> method.getName().equals("s" + getterName.substring(1)) && TypeArguments
							.sameType(TypeArguments.substitute(method.getGenericParameterTypes()[0], inherited), type))
					.findFirst().orElse(null);
			if (setter == null || !isPublicInstance(setter)) {
				continue;
			}

			String fieldName = Character.toLowerCase(getterName.charAt(3)) + getterName.substring(4);
			ComponentInfo info = new ComponentInfo(clss, fieldName, TypeArguments.resolve(type, bindings));
			info.addMember(Role.GETTER, getter);
			info.addMember(Role.SETTER, setter);
			Class<?> erasure = info.getType();

			MethodHandle read = Members.virtual(clss, getter);
			MethodHandle write = Members.virtual(clss, setter);
			info.setReadMethod(read.asType(read.type().changeReturnType(erasure)));
			// A fluent setter returns its object, or another the old value; either is dropped.
			info.setWriteMethod(write.asType(MethodType.methodType(void.class, write.type().parameterType(0), erasure)));

			getSetList.add(info);
			members.put(info, new Member(getter.getDeclaringClass(), getter, setter));
		}

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
				for (ComponentInfo.Member merged : field.members()) {
					existingField.addMember(merged.role(), merged.element());
				}
			}
		}

		// A private field is read where the class declaring the component's pair or field declares it; one the
		// constructor stores is a member already. A super class may declare a field of the component's name too, and
		// its annotation is not the component's.
		Set<java.lang.reflect.Field> behind = new HashSet<>();
		List<ComponentInfo> found = new ArrayList<>(fields);
		found.addAll(getSetList);
		for (ComponentInfo component : found) {
			Class<?> declarer = declarer(component, members);
			java.lang.reflect.Field privateField = privateField(declarer != null ? declarer : clss, component.getName());
			if (privateField != null) {
				component.addMember(Role.PRIVATE_FIELD, privateField);
				behind.add(privateField);
			}
		}
		// A private field of no component is off the wire already, unless @Field puts it there.
		for (java.lang.reflect.Field classField : clss.getDeclaredFields()) {
			if (isPrivateInstance(classField) && !behind.contains(classField)
					&& classField.isAnnotationPresent(Field.class)) {
				throw new CodeAnalysisException(String
						.format("Could not find matching getter/setter for field '%s'", classField.getName()));
			}
		}

		// Reflection finds them in no fixed order. Sorted once every member is known, since any may name one.
		getSetList.sort(Comparator.comparing(ComponentAnnotations::wireName));
		fields.addAll(getSetList);

		// A pair the class inherits holds a value apart from any other component of its name, which the wire would
		// carry only one of.
		for (ComponentInfo pair : getSetList) {
			Member member = members.get(pair);
			if (member.getter() == null
					|| member.getter().getDeclaringClass() == clss && member.setter().getDeclaringClass() == clss) {
				continue;
			}
			String name = ComponentAnnotations.wireName(pair);
			if (fields.stream()
					.anyMatch(other -> other != pair && ComponentAnnotations.wireName(other).equals(name))) {
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
	 * @return the class declaring {@code component}'s field or pair, or null for a constructor's component only
	 *         {@code @Field} names
	 */
	private static Class<?> declarer(ComponentInfo component, Map<ComponentInfo, Member> members) {
		Member member = members.get(component);
		return member != null ? member.declarer() : component.getDeclaringClass();
	}

	private static boolean isPrivateInstance(java.lang.reflect.Field field) {
		int modifiers = field.getModifiers();
		return Modifier.isPrivate(modifiers) && !Modifier.isTransient(modifiers) && !Modifier.isStatic(modifiers);
	}

	private static java.lang.reflect.Field privateField(Class<?> declarer, String name) {
		try {
			java.lang.reflect.Field field = declarer.getDeclaredField(name);
			return isPrivateInstance(field) ? field : null;
		} catch (NoSuchFieldException e) {
			return null;
		}
	}

	private static String qualified(Method method) {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName();
	}

	private static boolean isPublicInstance(Method method) {
		int modifiers = method.getModifiers();
		return Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers);
	}

}
