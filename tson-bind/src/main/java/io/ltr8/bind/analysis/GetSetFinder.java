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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
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
			throws SecurityException, CodeAnalysisException {

		List<ComponentInfo> getSetList = new ArrayList<>();

		Lookup lookup = MethodHandles.publicLookup();

		// Look for public non-transient fields.
		java.lang.reflect.Field[] classFields = clss.getFields();
		for (java.lang.reflect.Field classField : classFields) {
			int modifiers = classField.getModifiers();
			if (Modifier.isPublic(modifiers) && !Modifier.isTransient(modifiers) && !Modifier.isStatic(modifiers)) {

				ComponentInfo info = new ComponentInfo(classField.getName(), classField.getType());

				Field fieldAnnotation = classField.getAnnotation(Field.class);
				if (fieldAnnotation != null) {
					// Found a matching setter/getter pair, so add to fields.
					info.setField(fieldAnnotation);
				}

				try {
					info.setReadMethod(lookup.unreflectGetter(classField));
					info.setWriteMethod(lookup.unreflectSetter(classField));
				} catch (IllegalAccessException e) {
					// not expecting an error here.
					throw new SecurityException("Failed to unreflect field", e);
				}

				Type returnType = classField.getGenericType();
				if (returnType instanceof ParameterizedType) {
					info.setParamType((ParameterizedType) returnType);
				}

				getSetList.add(info);

			}
		}

		// https://stackoverflow.com/questions/5001172/java-reflection-getting-fields-and-methods-in-declaration-order/38929813
		// declared methods are in no particular order and it is not possible to discover it.
		// Many mention byte code analysis and line number tables, but that is asking for trouble when code
		// is compiled for production.
		Method[] methods = clss.getDeclaredMethods();
		for (Method method : methods) {

			// find the setters.
			if (method.getName().startsWith("set") && method.getName().length() > 3) {
				String getterName = "g" + method.getName().substring(1);

				try {
					// If this throws NoSuchMethodException we ignore field.
					Method getter = clss.getDeclaredMethod(getterName);

					String fieldName = Character.toLowerCase(getterName.charAt(3)) + getterName.substring(4);

					// Found a matching setter/getter pair, so add to fields.
					ComponentInfo info = new ComponentInfo(fieldName, getter.getReturnType());

					Field getterField = getter.getAnnotation(Field.class);
					if (getterField != null) {
						// Found a matching setter/getter pair, so add to fields.
						info.setField(getterField);
					}

					Field setterField = method.getAnnotation(Field.class);
					if (setterField != null) {
						// Found a matching setter/getter pair, so add to fields.
						info.setField(setterField);
					}

					try {
						info.setReadMethod(lookup.unreflect(getter));
						info.setWriteMethod(lookup.unreflect(method));
					} catch (IllegalAccessException e) {
						// not expecting an error here.
						throw new SecurityException("Failed to unreflect field", e);
					}

					Type returnType = method.getGenericReturnType();
					if (returnType instanceof ParameterizedType) {
						info.setParamType((ParameterizedType) returnType);
					}

					getSetList.add(info);

				} catch (NoSuchMethodException ex) {
					// ignore this field, no matching getter.
					continue;
				}
			}
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

		// merge any fields already found previously
		ListIterator<ComponentInfo> iter = getSetList.listIterator();
		while (iter.hasNext()) {
			ComponentInfo field = iter.next();

			for (ComponentInfo existingField : fields) {
				if (field.getName().equalsIgnoreCase(existingField.getName())) {

					// remove from list to add.
					iter.remove();

					existingField.setWriteMethod(field.getWriteMethod());
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

				Field fieldAnnotation = classField.getAnnotation(Field.class);
				if (fieldAnnotation != null) {

					ComponentInfo info = fields.stream().filter(e -> e.getName().equals(classField.getName()))
							.findFirst().orElse(null);
					if (info == null) {
						throw new CodeAnalysisException(String
								.format("Could not find matching getter/setter for field '%s'", classField.getName()));
					}

					// Found a matching setter/getter pair, so add to fields.
					info.setField(fieldAnnotation);
				}
			}
		}

	}

}
