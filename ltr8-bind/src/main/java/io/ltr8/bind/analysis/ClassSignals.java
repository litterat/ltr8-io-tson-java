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

import io.ltr8.annotation.Annotated;
import io.ltr8.annotation.Atom;
import io.ltr8.annotation.FieldOrder;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Tuple;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Union;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * What a class says about itself at class level, read in one pass: the binding marks it carries, the JDK's facts
 * about it, and the class-level annotations its binder applies. {@link Kind#of} decides its kind from
 * {@link #present} alone.
 */
record ClassSignals(Class<?> type, Set<Signal> present, Union union, Typename typename, FieldOrder fieldOrder) {

	enum Signal {
		/** The class is {@link Annotated} itself. */
		ANNOTATED_BOX,
		TRANSPARENT,
		TUPLE,
		RECORD_ON_TYPE,
		/** {@code @Record} on a public constructor; a constructor selection reads no other. */
		RECORD_ON_CONSTRUCTOR,
		UNION,
		ATOM_ON_TYPE,
		/** {@code @Atom} on a constructor the class declares. */
		ATOM_ON_CONSTRUCTOR,
		/** {@code @Atom} on a static method the class declares. */
		ATOM_ON_STATIC_METHOD,
		/** {@code @Atom} on an instance method the class declares or inherits from a super class. */
		ATOM_ON_INSTANCE_METHOD,
		/** The class implements {@link ToData}, itself or through a supertype. */
		TO_DATA,
		JDK_RECORD,
		ENUM,
		SEALED,
		INTERFACE,
		ABSTRACT,
		ARRAY,
		PRIMITIVE,
		MAP,
		COLLECTION
	}

	/** The marks that say a class is an atom: {@code @Atom} on itself or on a way in of its own. */
	static final Set<Signal> ATOM_MARKS = Set.of(Signal.ATOM_ON_TYPE, Signal.ATOM_ON_CONSTRUCTOR,
			Signal.ATOM_ON_STATIC_METHOD);

	ClassSignals {
		present = Set.copyOf(present);
	}

	static ClassSignals of(Class<?> type) {
		Set<Signal> present = EnumSet.noneOf(Signal.class);
		Union union = type.getAnnotation(Union.class);
		add(present, Signal.ANNOTATED_BOX, type == Annotated.class);
		add(present, Signal.TRANSPARENT, type.isAnnotationPresent(Transparent.class));
		add(present, Signal.TUPLE, type.isAnnotationPresent(Tuple.class));
		add(present, Signal.RECORD_ON_TYPE, type.isAnnotationPresent(Record.class));
		add(present, Signal.UNION, union != null);
		add(present, Signal.ATOM_ON_TYPE, type.isAnnotationPresent(Atom.class));
		add(present, Signal.TO_DATA, ToData.class.isAssignableFrom(type));
		add(present, Signal.JDK_RECORD, type.isRecord());
		add(present, Signal.ENUM, type.isEnum());
		add(present, Signal.SEALED, type.isSealed());
		add(present, Signal.INTERFACE, type.isInterface());
		add(present, Signal.ABSTRACT, Modifier.isAbstract(type.getModifiers()));
		add(present, Signal.ARRAY, type.isArray());
		add(present, Signal.PRIMITIVE, type.isPrimitive());
		add(present, Signal.MAP, Map.class.isAssignableFrom(type));
		add(present, Signal.COLLECTION, Collection.class.isAssignableFrom(type));
		for (Constructor<?> constructor : type.getDeclaredConstructors()) {
			add(present, Signal.RECORD_ON_CONSTRUCTOR, Modifier.isPublic(constructor.getModifiers())
					&& constructor.isAnnotationPresent(Record.class));
			add(present, Signal.ATOM_ON_CONSTRUCTOR, constructor.isAnnotationPresent(Atom.class));
		}
		for (Method method : type.getDeclaredMethods()) {
			add(present, Signal.ATOM_ON_STATIC_METHOD, isMarkedStatic(method));
		}
		for (Class<?> declarer = type; declarer != null; declarer = declarer.getSuperclass()) {
			for (Method method : declarer.getDeclaredMethods()) {
				// javac copies a method's annotations onto its bridges.
				add(present, Signal.ATOM_ON_INSTANCE_METHOD, !Modifier.isStatic(method.getModifiers())
						&& !method.isBridge() && !method.isSynthetic() && method.isAnnotationPresent(Atom.class));
			}
		}
		return new ClassSignals(type, present, union, type.getAnnotation(Typename.class),
				type.getAnnotation(FieldOrder.class));
	}

	static boolean isMarkedStatic(Method method) {
		return Modifier.isStatic(method.getModifiers()) && !method.isSynthetic()
				&& method.isAnnotationPresent(Atom.class);
	}

	private static void add(Set<Signal> present, Signal signal, boolean holds) {
		if (holds) {
			present.add(signal);
		}
	}

	boolean has(Signal signal) {
		return present.contains(signal);
	}

	Kind kind() {
		return Kind.of(present);
	}

	boolean marksAtom() {
		return !Collections.disjoint(present, ATOM_MARKS);
	}
}
