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
import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Profile;
import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Unbound;
import io.ltr8.annotation.Union;
import io.ltr8.bind.analysis.ComponentInfo.Member;
import io.ltr8.bind.analysis.ComponentInfo.Role;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A component's {@link Field @Field}, {@link Union @Union} and {@link Unbound @Unbound}, read from every member its
 * finder recorded.
 *
 * <p>{@code @Field} is read attribute by attribute: an attribute at its default is not stated, since an annotation
 * cannot tell a default from a value written equal to it, so {@code required} is true where any member states it. A
 * {@code @Profile} constructor's parameter {@linkplain Role#prevails() prevails}: an attribute it states, and a
 * {@code @Union} it carries, is the component's whatever the record's own members carry, since a profile binds
 * another shape than the record's header describes.
 */
final class ComponentAnnotations {

	private static final Set<Class<?>> OPTIONALS = Set.of(Optional.class, OptionalInt.class, OptionalLong.class,
			OptionalDouble.class);

	private ComponentAnnotations() {
	}

	private enum Attribute {
		VALUE {
			@Override
			Object stated(Field field) {
				return field.value().isBlank() ? null : field.value();
			}

			@Override
			String show(Object value) {
				return "value = \"" + value + "\"";
			}
		},
		REQUIRED {
			@Override
			Object stated(Field field) {
				return field.required() ? Boolean.TRUE : null;
			}

			@Override
			String show(Object value) {
				return "required = " + value;
			}
		},
		BRIDGE {
			@Override
			Object stated(Field field) {
				return field.bridge() == DataBridge.class ? null : field.bridge();
			}

			@Override
			String show(Object value) {
				return "bridge = " + ((Class<?>) value).getName() + ".class";
			}
		};

		/** The value {@code field} states, or null where it leaves this attribute at its default. */
		abstract Object stated(Field field);

		abstract String show(Object value);
	}

	/** Settles each component's annotations, once every finder has recorded its members. */
	static void read(List<ComponentInfo> components) {
		for (ComponentInfo component : components) {
			component.annotate(wireName(component), stated(component, Attribute.REQUIRED) != null,
					(Class<?>) stated(component, Attribute.BRIDGE), union(component), unbound(component));
		}
	}

	/**
	 * Refuses {@code member} where it states a {@code @Field} attribute, or carries a {@code @Union}, that differs
	 * from one a member of {@code component} already recorded gives. Refused as the member is found, so a class with
	 * another fault besides is refused for whichever the finders meet first; a finder records a prevailing member
	 * before the members it prevails over.
	 */
	static void admit(ComponentInfo component, Member member) throws CodeAnalysisException {
		if (member.role().prevails()) {
			return;
		}
		Field field = member.element().getAnnotation(Field.class);
		if (field != null) {
			for (Attribute attribute : Attribute.values()) {
				Object value = attribute.stated(field);
				if (value == null || prevailing(component, attribute) != null) {
					continue;
				}
				for (Member held : component.members()) {
					Object other = stated(held, attribute);
					if (other != null && !other.equals(value)) {
						throw differ(component, "@Field(" + attribute.show(other) + ")", held,
								"@Field(" + attribute.show(value) + ")", member);
					}
				}
			}
		}
		Union union = member.element().getAnnotation(Union.class);
		if (union != null && prevailingUnion(component) == null) {
			for (Member held : component.members()) {
				Union other = held.element().getAnnotation(Union.class);
				if (other != null && !other.equals(union)) {
					throw differ(component, show(other), held, show(union), member);
				}
			}
		}
	}

	/**
	 * Refuses a binding annotation that nothing applies, once {@link #read} has settled {@code components}: one an
	 * {@code @Unbound} component, the {@link Annotations} carrier or a component whose data class something else
	 * decides never reads, and one on a field or method of {@code described} or a super class, or a field or
	 * instance method of an interface either implements, or on such a method's parameter, that is a member of no
	 * component. A class declaring a {@code @Profile} constructor is spared the last for its instance members, which
	 * may be another profile's components.
	 */
	static void refuseUnapplied(Class<?> described, List<ComponentInfo> components) throws CodeAnalysisException {
		Set<AnnotatedElement> members = new HashSet<>();
		for (ComponentInfo component : components) {
			for (Member member : component.members()) {
				refuseUnapplied(component, member);
				members.add(member.element());
			}
		}
		boolean profiled = Stream.of(described.getDeclaredConstructors())
				.anyMatch(constructor -> constructor.isAnnotationPresent(Profile.class));
		for (Class<?> declarer : supertypes(described)) {
			for (AnnotatedElement declared : declaredMembers(declarer)) {
				// A class inherits no static method of an interface.
				boolean inherited = !declarer.isInterface() || !(declared instanceof Method) || !isStatic(declared);
				if (!inherited || profiled && !isStatic(declared)) {
					continue;
				}
				for (AnnotatedElement element : withParameters(declared)) {
					String annotation = bindingAnnotation(element);
					if (annotation != null && !members.contains(element)) {
						throw new CodeAnalysisException(String.format(
								"%s's %s carries %s, but is a member of no component, so nothing reads it",
								described.getName(), describe(element), annotation));
					}
				}
			}
		}
	}

	/**
	 * The methods declared in {@code described} or a supertype of it, other than {@code method}, that a call of
	 * {@code method} on a {@code described} dispatches through: one it overrides, and one overriding it. A bridge or
	 * synthetic method is the compiler's, and carries what the method it forwards to does.
	 */
	static List<Method> dispatchedWith(Class<?> described, Method method) {
		List<Method> dispatched = new ArrayList<>();
		if (isStatic(method) || Modifier.isPrivate(method.getModifiers())) {
			return dispatched;
		}
		Map<TypeVariable<?>, Type> bindings = TypeArguments.implemented(described);
		for (Class<?> declarer : supertypes(described)) {
			for (Method other : declarer.getDeclaredMethods()) {
				if (!other.equals(method) && !other.isBridge() && !other.isSynthetic() && !isStatic(other)
						&& !Modifier.isPrivate(other.getModifiers()) && other.getName().equals(method.getName())
						&& sameParameters(method, other, bindings) && overrides(method, other)) {
					dispatched.add(other);
				}
			}
		}
		return dispatched;
	}

	/**
	 * Whether the lower of two methods of one signature overrides the upper: the upper is reached from the lower's
	 * class (JLS 8.4.8.1). Neither is private or static, and a class's method overrides an interface's.
	 */
	private static boolean overrides(Method method, Method other) {
		boolean below = method.getDeclaringClass().isAssignableFrom(other.getDeclaringClass());
		Method upper = below ? method : other;
		Method lower = below ? other : method;
		int modifiers = upper.getModifiers();
		return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)
				|| upper.getDeclaringClass().getPackageName().equals(lower.getDeclaringClass().getPackageName())
						&& upper.getDeclaringClass().getClassLoader() == lower.getDeclaringClass().getClassLoader();
	}

	/** Of one erasure, or of one type once the type arguments {@code described} supplies are substituted. */
	private static boolean sameParameters(Method method, Method other, Map<TypeVariable<?>, Type> bindings) {
		if (Arrays.equals(method.getParameterTypes(), other.getParameterTypes())) {
			return true;
		}
		Type[] these = method.getGenericParameterTypes();
		Type[] those = other.getGenericParameterTypes();
		if (these.length != those.length) {
			return false;
		}
		for (int i = 0; i < these.length; i++) {
			if (!TypeArguments.sameType(TypeArguments.substitute(these[i], bindings),
					TypeArguments.substitute(those[i], bindings))) {
				return false;
			}
		}
		return true;
	}

	/** {@code type}, its super classes short of {@link Object} and {@link java.lang.Record}, and every interface. */
	private static Set<Class<?>> supertypes(Class<?> type) {
		Set<Class<?>> supertypes = new LinkedHashSet<>();
		for (Class<?> declarer = type; declarer != null && declarer != Object.class
				&& declarer != java.lang.Record.class; declarer = declarer.getSuperclass()) {
			supertypes.add(declarer);
		}
		for (Class<?> declarer : List.copyOf(supertypes)) {
			addInterfaces(declarer, supertypes);
		}
		return supertypes;
	}

	private static void addInterfaces(Class<?> type, Set<Class<?>> supertypes) {
		for (Class<?> declared : type.getInterfaces()) {
			if (supertypes.add(declared)) {
				addInterfaces(declared, supertypes);
			}
		}
	}

	private static void refuseUnapplied(ComponentInfo component, Member member) throws CodeAnalysisException {
		Field field = member.element().getAnnotation(Field.class);
		Union union = member.element().getAnnotation(Union.class);
		String stated = field != null && field.required() ? "@Field(" + Attribute.REQUIRED.show(true) + ")"
				: field != null && field.bridge() != DataBridge.class
						? "@Field(" + Attribute.BRIDGE.show(field.bridge()) + ")"
						: union != null ? show(union) : null;
		if (component.isUnbound() && stated != null) {
			throw new CodeAnalysisException(String.format(
					"%s's component '%s' is @Unbound, so nothing reads or writes it, but %s carries %s",
					component.getDescribed().getName(), component.getName(), describe(member), stated));
		}
		if (component.getType() == Annotations.class) {
			// Always present, so required = true is true of it already.
			String unread = field != null && field.bridge() != DataBridge.class
					? "@Field(" + Attribute.BRIDGE.show(field.bridge()) + ")" : union != null ? show(union) : null;
			if (unread != null) {
				throw new CodeAnalysisException(String.format("%s's component '%s' is its Annotations carrier, "
						+ "filled from the value's own annotations, but %s carries %s",
						component.getDescribed().getName(), component.getName(), describe(member), unread));
			}
			return;
		}
		String decides = component.getBridge() != null
				? "its @Field(" + Attribute.BRIDGE.show(component.getBridge()) + ")"
				: OPTIONALS.contains(component.getType()) ? "its type " + component.getType().getName() : null;
		if (union != null && decides != null) {
			throw new CodeAnalysisException(String.format(
					"%s's component '%s' has %s on %s, which nothing consults: %s decides its data class",
					component.getDescribed().getName(), component.getName(), show(union), describe(member), decides));
		}
	}

	/**
	 * Refuses {@code record}, which its binder writes without reading any binding annotation of its own, where one
	 * of its fields or methods, a parameter of one of its methods, or a parameter of its canonical constructor,
	 * carries one.
	 *
	 * @param written how the binder writes it, completing "{@code record} ..."
	 */
	static void refuseAny(Class<?> record, String written) throws CodeAnalysisException {
		List<AnnotatedElement> elements = new ArrayList<>();
		for (AnnotatedElement element : declaredMembers(record)) {
			elements.addAll(withParameters(element));
		}
		Class<?>[] types = Stream.of(record.getRecordComponents()).map(RecordComponent::getType)
				.toArray(Class<?>[]::new);
		try {
			elements.addAll(List.of(record.getDeclaredConstructor(types).getParameters()));
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(String.format("Record %s declares no canonical constructor",
					record.getName()), e);
		}
		refuse(record, written, elements);
	}

	/**
	 * Refuses {@code type}, whose binder writes it without reading any binding annotation of its own, where one of
	 * its fields or methods, a parameter of one of its methods or constructors, or a member of an enum constant's
	 * body or a parameter of one of its methods carries one. Where a subclass described by its own components may
	 * extend {@code type}, an instance field or method a subclass reaches, and that method's parameters, may be
	 * members of one of its components, and are that subclass's to judge; so is an interface's method, which another
	 * class implementing it may have as a member, as an enum and a record sharing a union do.
	 *
	 * @param written how the binder writes it, completing "{@code type} ..."
	 */
	static void refuseUnread(Class<?> type, String written) throws CodeAnalysisException {
		// A ToData class's subclass inherits ToData, and is refused as a class that does not declare it.
		boolean extended = !Modifier.isFinal(type.getModifiers()) && !type.isEnum()
				&& !ToData.class.isAssignableFrom(type);
		List<AnnotatedElement> elements = new ArrayList<>();
		for (AnnotatedElement element : declaredMembers(type)) {
			if (!extended || isStatic(element) || element instanceof Method method
					&& Modifier.isPrivate(method.getModifiers())) {
				elements.addAll(withParameters(element));
			}
		}
		for (Constructor<?> constructor : type.getDeclaredConstructors()) {
			elements.addAll(declaredParameters(constructor));
		}
		refuse(type, written, elements);
		if (type.isEnum()) {
			for (Object constant : type.getEnumConstants()) {
				if (constant.getClass() == type) {
					continue;
				}
				for (AnnotatedElement declared : declaredMembers(constant.getClass())) {
					for (AnnotatedElement element : withParameters(declared)) {
						String annotation = bindingAnnotation(element);
						if (annotation != null) {
							throw new CodeAnalysisException(String.format(
									"%s %s, so nothing reads %s on %s of the body of its constant %s",
									type.getName(), written, annotation, describeInBody(element),
									((Enum<?>) constant).name()));
						}
					}
				}
			}
		}
	}

	/** An enum constant's body is an anonymous class, so names no class. */
	private static String describeInBody(AnnotatedElement element) {
		return switch (element) {
			case java.lang.reflect.Field field -> "the field " + field.getName();
			case Method method -> "the method " + method.getName() + parameters(method);
			case Parameter parameter -> {
				Executable method = parameter.getDeclaringExecutable();
				yield "parameter " + declaredParameters(method).indexOf(parameter) + " of the method "
						+ method.getName() + parameters(method);
			}
			default -> throw new IllegalStateException(element + " is no field, method or parameter");
		};
	}

	/**
	 * Refuses {@code protocol}, read as its exchanges, where a field or method of it or an interface it extends, or
	 * a method's parameter, carries a binding annotation: an exchange is a method, and none is a component.
	 */
	static void refuseInProtocol(Class<?> protocol) throws CodeAnalysisException {
		List<AnnotatedElement> elements = new ArrayList<>();
		for (Class<?> declarer : supertypes(protocol)) {
			for (AnnotatedElement element : declaredMembers(declarer)) {
				elements.add(element);
				if (element instanceof Method method) {
					elements.addAll(List.of(method.getParameters()));
				}
			}
		}
		refuse(protocol, "is a protocol, read as its exchanges", elements);
	}

	private static void refuse(Class<?> type, String written, List<AnnotatedElement> elements)
			throws CodeAnalysisException {
		for (AnnotatedElement element : elements) {
			String annotation = bindingAnnotation(element);
			if (annotation != null) {
				throw new CodeAnalysisException(String.format("%s %s, so nothing reads %s on %s", type.getName(),
						written, annotation, element instanceof Parameter ? describe(element)
								: "the " + describe(element).replaceFirst("^static ", "")));
			}
		}
	}

	/** The fields and methods {@code declarer} declares itself; a bridge or synthetic member is the compiler's. */
	private static List<AnnotatedElement> declaredMembers(Class<?> declarer) {
		List<AnnotatedElement> declared = new ArrayList<>();
		for (java.lang.reflect.Field field : declarer.getDeclaredFields()) {
			if (!field.isSynthetic()) {
				declared.add(field);
			}
		}
		for (Method method : declarer.getDeclaredMethods()) {
			if (!method.isSynthetic() && !method.isBridge()) {
				declared.add(method);
			}
		}
		return declared;
	}

	/** {@code member}, and the parameters its source declares where it is a method. */
	private static List<AnnotatedElement> withParameters(AnnotatedElement member) {
		List<AnnotatedElement> elements = new ArrayList<>(List.of(member));
		if (member instanceof Method method) {
			elements.addAll(declaredParameters(method));
		}
		return elements;
	}

	private static String bindingAnnotation(AnnotatedElement element) {
		return element.isAnnotationPresent(Field.class) ? "@Field"
				: element.isAnnotationPresent(Union.class) ? "@Union"
						: element.isAnnotationPresent(Unbound.class) ? "@Unbound" : null;
	}

	private static boolean isStatic(AnnotatedElement element) {
		return element instanceof java.lang.reflect.Member member && Modifier.isStatic(member.getModifiers());
	}

	private static String describe(AnnotatedElement element) {
		String modifier = isStatic(element) ? "static " : "";
		return switch (element) {
			case java.lang.reflect.Field field -> modifier + "field " + field.getDeclaringClass().getSimpleName() + "."
					+ field.getName();
			case Method method -> modifier + "method " + method.getDeclaringClass().getSimpleName() + "."
					+ method.getName() + parameters(method);
			case Parameter parameter -> {
				Executable executable = parameter.getDeclaringExecutable();
				List<Parameter> declared = declaredParameters(executable);
				yield "parameter " + declared.indexOf(parameter) + " of the "
						+ (executable instanceof Method method ? "method " + method.getDeclaringClass().getSimpleName()
								+ "." + method.getName() : "constructor " + executable.getDeclaringClass().getSimpleName())
						+ declared.stream().map(declaredParameter -> declaredParameter.getType().getSimpleName())
								.collect(Collectors.joining(", ", "(", ")"));
			}
			default -> throw new IllegalStateException(element + " is no field, method or parameter");
		};
	}

	/**
	 * The parameters {@code executable}'s source declares. A class file without parameter metadata marks none
	 * implicit, and javac passes an enum constructor its constant's name and ordinal first, as
	 * {@link Constructor#getParameterAnnotations} also assumes.
	 */
	private static List<Parameter> declaredParameters(Executable executable) {
		Parameter[] parameters = executable.getParameters();
		List<Parameter> declared = Stream.of(parameters)
				.filter(parameter -> !parameter.isSynthetic() && !parameter.isImplicit()).toList();
		return executable instanceof Constructor<?> && executable.getDeclaringClass().isEnum()
				&& declared.size() == parameters.length && parameters.length >= 2
				&& parameters[0].getType() == String.class && parameters[1].getType() == int.class
						? declared.subList(2, declared.size()) : declared;
	}

	/**
	 * The wire name {@code component}'s members give so far, for a finder naming components before {@link #read}
	 * settles them.
	 */
	static String wireName(ComponentInfo component) {
		Object value = stated(component, Attribute.VALUE);
		return value != null ? (String) value : component.getName();
	}

	/**
	 * Whether a member {@code component}'s finder has recorded so far marks it {@code @Unbound}, for a finder
	 * judging components before {@link #read} settles them.
	 */
	static boolean unbound(ComponentInfo component) {
		return component.members().stream().anyMatch(member -> member.element().isAnnotationPresent(Unbound.class));
	}

	private static Object stated(ComponentInfo component, Attribute attribute) {
		Object prevailing = prevailing(component, attribute);
		if (prevailing != null) {
			return prevailing;
		}
		for (Member member : component.members()) {
			Object value = stated(member, attribute);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static Object prevailing(ComponentInfo component, Attribute attribute) {
		for (Member member : component.members()) {
			if (member.role().prevails()) {
				return stated(member, attribute);
			}
		}
		return null;
	}

	private static Object stated(Member member, Attribute attribute) {
		Field field = member.element().getAnnotation(Field.class);
		return field == null ? null : attribute.stated(field);
	}

	private static Union union(ComponentInfo component) {
		Union prevailing = prevailingUnion(component);
		if (prevailing != null) {
			return prevailing;
		}
		for (Member member : component.members()) {
			Union union = member.element().getAnnotation(Union.class);
			if (union != null) {
				return union;
			}
		}
		return null;
	}

	private static Union prevailingUnion(ComponentInfo component) {
		for (Member member : component.members()) {
			if (member.role().prevails()) {
				return member.element().getAnnotation(Union.class);
			}
		}
		return null;
	}

	private static CodeAnalysisException differ(ComponentInfo component, String held, Member heldBy, String given,
			Member givenBy) {
		return new CodeAnalysisException(String.format("%s's component '%s' has %s on %s but %s on %s",
				component.getDescribed().getName(), component.getName(), held, describe(heldBy), given,
				describe(givenBy)));
	}

	private static String show(Union union) {
		String members = Arrays.stream(union.value()).map(member -> member.getName() + ".class")
				.collect(Collectors.joining(", ", "{", "}"));
		return union.sealed() ? "@Union(" + members + ")" : "@Union(value = " + members + ", sealed = false)";
	}

	private static String describe(Member member) {
		return switch (member.element()) {
			case java.lang.reflect.Field field -> "the " + member.role().noun() + " "
					+ field.getDeclaringClass().getSimpleName() + "." + field.getName();
			case Method method -> "the " + member.role().noun() + " " + method.getDeclaringClass().getSimpleName()
					+ "." + method.getName() + parameters(method);
			case Parameter parameter -> {
				Executable executable = parameter.getDeclaringExecutable();
				yield "parameter " + List.of(executable.getParameters()).indexOf(parameter) + " of the "
						+ member.role().noun() + " " + executable.getDeclaringClass().getSimpleName()
						+ (executable instanceof Method method ? "." + method.getName() : "") + parameters(executable);
			}
			case RecordComponent component -> "the " + member.role().noun() + " "
					+ component.getDeclaringRecord().getSimpleName() + "." + component.getName();
			default -> throw new IllegalStateException(member.element() + " is no member of a component");
		};
	}

	private static String parameters(Executable executable) {
		return Arrays.stream(executable.getParameterTypes()).map(Class::getSimpleName)
				.collect(Collectors.joining(", ", "(", ")"));
	}
}
