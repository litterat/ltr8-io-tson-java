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

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.FieldOrder;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A record class's components in wire order -- what its finders find, annotated, named apart and put in its
 * {@link FieldOrder} -- and where each one's value sits in the values array a reader fills.
 */
final class RecordComponents {

	private RecordComponents() {
	}

	static List<ComponentInfo> find(Class<?> targetClass, Type parameterizedType, Constructor<?> ctor)
			throws CodeAnalysisException {
		// This is a data class so need to decide what is/isn't data.
		List<ComponentInfo> components = new ArrayList<>();

		Map<TypeVariable<?>, Type> bindings = TypeArguments.described(targetClass, parameterizedType);

		if (targetClass.isRecord()) {
			// A genuine Java record's components come straight from the JDK's own
			// RecordComponent API -- authoritative, no guessing needed, and a record can't have
			// setters, so there's nothing for GetSetFinder to add.
			new RecordComponentFinder().findComponents(targetClass, bindings, ctor, components);
		} else {
			// A hand-written tuple class (identified by @Record, ClassBinder.isRecord()).
			// ImmutableFinder recovers constructor-set (immutable) fields via bytecode analysis;
			// GetSetFinder then adds any additional mutable getter/setter fields on top -- a
			// single class can mix both (see MixedImmutable in ltr8-bind's own tests).
			new ImmutableFinder().findComponents(targetClass, bindings, ctor, components);
			new GetSetFinder().findComponents(targetClass, bindings, ctor, components);
			receiveTargetClass(targetClass, components);
		}
		ComponentAnnotations.read(components);

		refuseSharedNames(targetClass, components);

		// Modify the order of fields if order annotation supplied.
		FieldOrder dataOrder = targetClass.getAnnotation(FieldOrder.class);
		if (dataOrder != null) {
			reorderFields(targetClass, dataOrder, components);
		}
		return components;
	}

	static int[] slots(List<ComponentInfo> components) {
		// A component the class marked @Unbound is not on the wire: nothing fills it from a document and
		// nothing writes it. So the values array a reader fills holds only the bound components, densely --
		// `slots` maps a component's position to its place in that array, or -1 for one the class owns,
		// which the constructor supplies as its type's zero. Keeping the array dense is what lets fields().length stay
		// the only notion of size anyone needs.
		int[] slots = new int[components.size()];
		int nextSlot = 0;
		for (int x = 0; x < components.size(); x++) {
			slots[x] = components.get(x).isUnbound() ? -1 : nextSlot++;
		}
		return slots;
	}

	/**
	 * A member declared on a super class unreflects to a handle taking that class, and a combinator joining it to
	 * a handle taking the described class demands the receivers match exactly. Adapted before anything is composed
	 * from them, so every handle a {@link DataClassField} exposes, and the setters the constructor folds in, take
	 * the described class.
	 */
	private static void receiveTargetClass(Class<?> targetClass, List<ComponentInfo> components) {
		for (ComponentInfo info : components) {
			MethodHandle read = info.getReadMethod();
			if (read != null) {
				info.setReadMethod(read.asType(read.type().changeParameterType(0, targetClass)));
			}
			MethodHandle write = info.getWriteMethod();
			if (write != null) {
				info.setWriteMethod(write.asType(write.type().changeParameterType(0, targetClass)));
			}
		}
	}

	/**
	 * A name reaches one component, whichever finders found them: {@link DataClassRecord} indexes its fields by
	 * name, so of two sharing one, a map writes and reads one value for both. An {@code @Unbound} component counts,
	 * since a reader tells it apart by its name; the {@link Annotations} carrier does not, being filled from the
	 * value's own annotations and never by name.
	 */
	private static void refuseSharedNames(Class<?> targetClass, List<ComponentInfo> components)
			throws CodeAnalysisException {
		Set<String> names = new HashSet<>();
		for (ComponentInfo info : components) {
			String name = info.getWireName();
			if (info.getType() != Annotations.class && !names.add(name)) {
				throw new CodeAnalysisException(String.format("%s binds two components to the name '%s'; @Field on "
						+ "one of them names it apart", targetClass.getName(), name));
			}
		}
	}

	private static void reorderFields(Class<?> targetClass, FieldOrder dataOrder, List<ComponentInfo> components)
			throws CodeAnalysisException {

		List<String> fieldOrder = List.of(dataOrder.value());
		if (fieldOrder.size() != components.size()) {
			throw new CodeAnalysisException(String.format("%s has %d components but its @FieldOrder names %d: it "
					+ "names each component once", targetClass.getName(), components.size(), fieldOrder.size()));
		}

		Set<String> componentNames = new HashSet<>();
		for (ComponentInfo component : components) {
			if (!componentNames.add(component.getName())) {
				throw new CodeAnalysisException(String.format("%s has two components named '%s', which @FieldOrder "
						+ "cannot tell apart: it names a component by its Java name", targetClass.getName(),
						component.getName()));
			}
		}

		// With the counts equal, each name a component and none given twice, the order is a permutation.
		Set<String> named = new HashSet<>();
		for (String name : fieldOrder) {
			if (!componentNames.contains(name)) {
				throw new CodeAnalysisException(String.format("%s has no component named '%s', which its @FieldOrder "
						+ "names: it names a component by its Java name", targetClass.getName(), name));
			}
			if (!named.add(name)) {
				throw new CodeAnalysisException(String.format("%s has a @FieldOrder naming '%s' twice: it names each "
						+ "component once", targetClass.getName(), name));
			}
		}

		components.sort(Comparator.comparingInt((ComponentInfo component) -> fieldOrder.indexOf(component.getName())));
	}
}
