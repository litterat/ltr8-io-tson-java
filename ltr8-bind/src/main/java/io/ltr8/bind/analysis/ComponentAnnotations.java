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

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Unbound;
import io.ltr8.annotation.Union;
import io.ltr8.bind.analysis.ComponentInfo.Member;
import io.ltr8.bind.analysis.ComponentInfo.Role;

import java.util.List;

/**
 * A component's {@link Field @Field}, {@link Union @Union} and {@link Unbound @Unbound}, read from the members its
 * finder recorded. A member's {@link Role} decides which of the three it gives: every member can mark its component
 * unbound, and the members a {@code @Field} (or {@code @Union}) is read from must carry equal ones.
 *
 * <p>A record's component is read for {@code @Unbound} alone, since neither {@code @Field} nor {@code @Union} targets
 * {@code RECORD_COMPONENT}: written on a record header, they reach the field, the canonical constructor's parameter
 * and, where the compiler declares it, the accessor (JLS 8.10.3), but never the {@code RecordComponent}.
 */
final class ComponentAnnotations {

	private ComponentAnnotations() {
	}

	/** Settles each component's annotations, once every finder has recorded its members. */
	static void read(List<ComponentInfo> components) {
		for (ComponentInfo component : components) {
			component.annotate(field(component), union(component), unbound(component));
		}
	}

	/**
	 * Refuses {@code member} where the {@code @Field} or {@code @Union} it gives differs from one {@code component}'s
	 * members already give. Refused as the member is found, so a class with another fault besides is refused for
	 * whichever the finders meet first.
	 */
	static void admit(ComponentInfo component, Member member) throws CodeAnalysisException {
		Field field = fieldOf(member);
		Field held = field(component);
		if (field != null && held != null && !held.equals(field)) {
			throw new CodeAnalysisException(
					String.format("Field '%s' already has Field annotation", component.getName()));
		}
		Union union = unionOf(member);
		Union heldUnion = union(component);
		if (union != null && heldUnion != null && !heldUnion.equals(union)) {
			throw new CodeAnalysisException(
					String.format("Field '%s' has @Union annotations that differ", component.getName()));
		}
	}

	/**
	 * The {@code @Field} {@code component}'s members give so far, for a finder naming components before
	 * {@link #read} settles them.
	 */
	static Field field(ComponentInfo component) {
		for (Member member : component.members()) {
			Field field = fieldOf(member);
			if (field != null) {
				return field;
			}
		}
		return null;
	}

	private static Union union(ComponentInfo component) {
		for (Member member : component.members()) {
			Union union = unionOf(member);
			if (union != null) {
				return union;
			}
		}
		return null;
	}

	private static boolean unbound(ComponentInfo component) {
		return component.members().stream().anyMatch(member -> member.element().isAnnotationPresent(Unbound.class));
	}

	private static Field fieldOf(Member member) {
		return switch (member.role()) {
			case RECORD_ACCESSOR, PROFILED_PARAMETER, STORED_FIELD, PARAMETER, ACCESSOR, PUBLIC_FIELD, GETTER, SETTER,
					PRIVATE_FIELD -> member.element().getAnnotation(Field.class);
			case RECORD_COMPONENT, RECORD_PARAMETER, PROFILED_ACCESSOR, SHARED_PARAMETER, MERGED_PUBLIC_FIELD,
					MERGED_GETTER, MERGED_SETTER -> null;
		};
	}

	private static Union unionOf(Member member) {
		return switch (member.role()) {
			case RECORD_ACCESSOR, STORED_FIELD, PARAMETER, ACCESSOR -> member.element().getAnnotation(Union.class);
			case RECORD_COMPONENT, RECORD_PARAMETER, PROFILED_ACCESSOR, PROFILED_PARAMETER, SHARED_PARAMETER,
					PUBLIC_FIELD, GETTER, SETTER, PRIVATE_FIELD, MERGED_PUBLIC_FIELD, MERGED_GETTER, MERGED_SETTER -> null;
		};
	}
}
