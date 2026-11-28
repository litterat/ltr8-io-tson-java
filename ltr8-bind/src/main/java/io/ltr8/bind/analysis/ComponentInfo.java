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

import io.ltr8.annotation.Union;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Placeholder to collect information about field details. Used as a temporary hold during the
 * resolving process.
 */
public class ComponentInfo {

	// The class being described, which a member may be declared by a super class of.
	private final Class<?> described;

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

	private String wireName;

	private boolean required;

	// A DataBridge implementation, or null for none.
	private Class<?> bridge;

	private Union union;

	// Marked @Unbound: the component belongs to the class, not the wire, so nothing reads it from a document or writes
	// it to one. Its constructor slot takes its type's zero, and its setter is never called.
	private boolean unbound;


	public ComponentInfo(Class<?> described, String name, Type type) {
		this.described = described;
		this.name = name;
		this.type = type;
		this.constructorArgument = -1;
	}

	/** What a member is to its component, named as a refusal names it. */
	enum Role {
		/** A record component, which only {@code @Unbound} can target. */
		RECORD_COMPONENT("record component"),
		/** A record's field, which a record header's annotations always reach (JLS 8.10.3). */
		RECORD_FIELD("field"),
		RECORD_ACCESSOR("accessor"),
		/** A record's canonical constructor's parameter. */
		RECORD_PARAMETER("canonical constructor"),
		/** A {@code @Profile} constructor's parameter, which binds a record through another shape than its own. */
		PROFILED_PARAMETER("@Profile constructor"),
		/** The field {@link ImmutableFinder} found a constructor parameter stored in. */
		STORED_FIELD("field"),
		/** A {@code @Record} constructor's parameter. */
		PARAMETER("constructor"),
		/** A method {@link ImmutableFinder} matched as the component's accessor. */
		ACCESSOR("accessor"),
		PUBLIC_FIELD("public field"),
		GETTER("getter"),
		SETTER("setter"),
		/** The private field of the class declaring the component's pair or field, of the component's name. */
		PRIVATE_FIELD("private field"),
		/** A method of a super class or an interface that a member method overrides. */
		OVERRIDDEN("overridden method"),
		/** A method of a subclass that overrides a member method, and that a call of it dispatches to. */
		OVERRIDING("overriding method");

		private final String noun;

		Role(String noun) {
			this.noun = noun;
		}

		String noun() {
			return noun;
		}

		/** Whether what this member's annotations state is the component's, whatever the others state. */
		boolean prevails() {
			return this == PROFILED_PARAMETER;
		}
	}

	record Member(Role role, AnnotatedElement element) {
	}

	/**
	 * Refuses {@code element} as {@link ComponentAnnotations#admit} does. An element already a member, reached again
	 * by another finder or path, keeps the role it was first recorded with. A method brings every method a call of
	 * it dispatches through ({@link ComponentAnnotations#dispatchedWith}): an override inherits no annotation, and
	 * what any declaration of the call states is stated of the component. Each method brings its parameters, in the
	 * same role: a setter's is handed the component's value.
	 */
	void addMember(Role role, AnnotatedElement element) throws CodeAnalysisException {
		if (!add(role, element) || !(element instanceof Method method)) {
			return;
		}
		addParameters(role, method);
		for (Method other : ComponentAnnotations.dispatchedWith(described, method)) {
			Role dispatched = method.getDeclaringClass().isAssignableFrom(other.getDeclaringClass()) ? Role.OVERRIDING
					: Role.OVERRIDDEN;
			add(dispatched, other);
			addParameters(dispatched, other);
		}
	}

	private void addParameters(Role role, Method method) throws CodeAnalysisException {
		for (Parameter parameter : method.getParameters()) {
			add(role, parameter);
		}
	}

	private boolean add(Role role, AnnotatedElement element) throws CodeAnalysisException {
		if (members.stream().anyMatch(member -> member.element().equals(element))) {
			return false;
		}
		Member member = new Member(role, element);
		ComponentAnnotations.admit(this, member);
		members.add(member);
		return true;
	}

	List<Member> members() {
		return members;
	}

	void annotate(String wireName, boolean required, Class<?> bridge, Union union, boolean unbound) {
		this.wireName = wireName;
		this.required = required;
		this.bridge = bridge;
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

	public Class<?> getDescribed() {
		return described;
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

	/** The component's name on the wire: its {@code @Field} value, or its Java name. */
	public String getWireName() {
		requireAnnotated();
		return wireName;
	}

	public boolean isRequired() {
		requireAnnotated();
		return required;
	}

	/** The {@code DataBridge} class its {@code @Field} names, or null for none. */
	public Class<?> getBridge() {
		requireAnnotated();
		return bridge;
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
