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

import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Unbound;
import io.ltr8.annotation.Union;
import io.ltr8.bind.analysis.ComponentInfo.Member;
import io.ltr8.bind.analysis.ComponentInfo.Role;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
						+ parameters(executable);
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
