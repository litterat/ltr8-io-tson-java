/*
 * Copyright (c) 2020-2021, Litterat Pty Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ltr8.bind.analysis;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Union;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Placeholder to collect information about field details. Used as a temporary hold during the
 * resolving process.
 */
public class ComponentInfo {

	private String name;

	// A class, a parameterized type or an array of one (TypeArguments.isHeld): its erasure is the finder's class.
	private final Type type;

	// Declares the field a constructor stores this component in; null where only @Field names the component.
	private Class<?> declaringClass;

	private MethodHandle writeMethod;

	private MethodHandle readMethod;

	// Every method the constructor analysis matched as this component's accessor, not only the one read through: a
	// getter/setter pair whose getter is among them is a member of this component, whichever class declares it.
	private final List<Method> accessors = new ArrayList<>();

	private int constructorArgument;

	private final List<Member> members = new ArrayList<>();

	private boolean annotated;

	private Field field;

	private Union union;

	// Marked @Unbound: the component belongs to the class, not the wire, so nothing reads it from a document or writes
	// it to one. Its constructor slot takes its type's zero, and its setter is never called.
	private boolean unbound;


	public ComponentInfo(String name, Type type) {
		this.name = name;
		this.type = type;
		this.constructorArgument = -1;
	}

	/**
	 * What a member is to its component, and the finder and path that found it: {@link ComponentAnnotations} reads
	 * the annotations a member gives by its role.
	 */
	enum Role {
		/** A record component, on either of {@link RecordComponentFinder}'s paths. */
		RECORD_COMPONENT,
		/** A record's accessor, bound through its canonical constructor. */
		RECORD_ACCESSOR,
		/** A canonical constructor's parameter. */
		RECORD_PARAMETER,
		/** A record's accessor, bound through a {@code @Profile} constructor. */
		PROFILED_ACCESSOR,
		/** A {@code @Profile} constructor's parameter. */
		PROFILED_PARAMETER,
		/** The field {@link ImmutableFinder} found a constructor parameter stored in. */
		STORED_FIELD,
		/** A {@code @Record} constructor's parameter, of the first component storing it. */
		PARAMETER,
		/** A {@code @Record} constructor's parameter, of a further component storing it. */
		SHARED_PARAMETER,
		/** A method {@link ImmutableFinder} matched as the component's accessor. */
		ACCESSOR,
		/** {@link GetSetFinder}'s public field. */
		PUBLIC_FIELD,
		/** {@link GetSetFinder}'s getter. */
		GETTER,
		/** {@link GetSetFinder}'s setter. */
		SETTER,
		/** A private field of the described class whose name is that of a component the class declares. */
		PRIVATE_FIELD,
		/** A public field that writes a constructor's component. */
		MERGED_PUBLIC_FIELD,
		/** The getter of a pair that writes a constructor's component. */
		MERGED_GETTER,
		/** The setter of a pair that writes a constructor's component. */
		MERGED_SETTER;

		Role merged() {
			return switch (this) {
				case PUBLIC_FIELD -> MERGED_PUBLIC_FIELD;
				case GETTER -> MERGED_GETTER;
				case SETTER -> MERGED_SETTER;
				default -> throw new IllegalStateException(this + " is no member of a pair or public field");
			};
		}
	}

	record Member(Role role, AnnotatedElement element) {
	}

	/** Refuses {@code element} as {@link ComponentAnnotations#admit} does. */
	void addMember(Role role, AnnotatedElement element) throws CodeAnalysisException {
		Member member = new Member(role, element);
		ComponentAnnotations.admit(this, member);
		members.add(member);
	}

	List<Member> members() {
		return members;
	}

	void annotate(Field field, Union union, boolean unbound) {
		this.field = field;
		this.union = union;
		this.unbound = unbound;
		this.annotated = true;
	}

	public boolean isUnbound() {
		requireAnnotated();
		return unbound;
	}

	private void requireAnnotated() {
		if (!annotated) {
			throw new IllegalStateException(String.format("Component '%s' is read before its annotations are", name));
		}
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public Class<?> getDeclaringClass() {
		return declaringClass;
	}

	public void setDeclaringClass(Class<?> declaringClass) {
		this.declaringClass = declaringClass;
	}

	public Field getField() {
		requireAnnotated();
		return this.field;
	}

	public Union getUnion() {
		requireAnnotated();
		return this.union;
	}



	public Class<?> getType() {
		return TypeArguments.erasure(type);
	}

	public Type getGenericType() {
		return type;
	}

	public MethodHandle getWriteMethod() {
		return writeMethod;
	}

	public void setWriteMethod(MethodHandle writeMethod) {
		this.writeMethod = writeMethod;
	}

	public MethodHandle getReadMethod() {
		return readMethod;
	}

	public void setReadMethod(MethodHandle readMethod) {
		this.readMethod = readMethod;
	}

	public void addAccessor(Method accessor) {
		accessors.add(accessor);
	}

	public boolean isAccessor(Method method) {
		return accessors.contains(method);
	}

	public int getConstructorArgument() {
		return constructorArgument;
	}

	public void setConstructorArgument(int constructorArgument) {
		this.constructorArgument = constructorArgument;
	}

}
