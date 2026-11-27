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

import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Record;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The public constructor a record class is built through, chosen by the context's {@link Profile} and the class's
 * {@link Record}, and failing both, a lone constructor or a Java record's canonical one.
 */
final class RecordConstructors {

	private RecordConstructors() {
	}

	static Constructor<?> getConstructor(Class<?> dataClass, String profile) throws CodeAnalysisException {
		Constructor<?>[] declared = dataClass.getConstructors();
		Constructor<?>[] constructors = declared;

		// A profiled constructor wins, and wins ahead of everything -- including the single-constructor
		// shortcut, so a class with one constructor and a profile on it still means what it says. Selection
		// is by name equality and nothing here knows what the name stands for; see @Profile.
		if (profile != null) {
			for (Constructor<?> constructor : constructors) {
				Profile annotation = constructor.getAnnotation(Profile.class);
				if (annotation != null && List.of(annotation.value()).contains(profile)) {
					return constructor;
				}
			}
		}

		// Nothing serves this profile, so fall back -- but only among the constructors that claim no profile
		// of their own. Another profile's constructor is not a candidate for this one: it exists to bind a
		// shape this context is not asking for, and picking it would quietly bind the wrong version. With
		// those set aside a record's canonical constructor is the ordinary fallback, so naming a context
		// costs nothing for the classes that do not care.
		constructors = unprofiled(constructors, profile != null);

		// only one custructor. this must be it.
		if (constructors.length == 1) {
			return constructors[0];
		}

		// Does it have an annotation?
		for (Constructor<?> constructor : constructors) {
			Record dataAnnotation = constructor
					.getAnnotation(Record.class);
			if (dataAnnotation != null) {
				return constructor;
			}
		}

		Constructor<?> canonical = unprofiledCanonical(dataClass, constructors);
		if (canonical != null) {
			return canonical;
		}

		// Nothing designated at all -- not even a single unprofiled constructor, or a record's canonical one, to fall
		// back to. When the context named a profile, say so and list what the class does offer: the likeliest cause
		// is a name that disagrees in one of the two places it has to match, and the fallback would otherwise
		// surface later as a field mismatch against the wrong constructor.
		if (profile != null) {
			List<String> offered = new ArrayList<>();
			for (Constructor<?> constructor : declared) {
				Profile annotation = constructor.getAnnotation(Profile.class);
				if (annotation != null) {
					offered.addAll(List.of(annotation.value()));
				}
			}
			throw new CodeAnalysisException("No constructor for profile '" + profile + "' on " + dataClass.getName()
					+ (offered.isEmpty()
							? " -- it declares no @Profile constructors, and none is designated with @Record"
							: ", which offers " + offered + " -- and none is designated with @Record as the "
									+ "fallback for profiles it does not name"));
		}

		throw new CodeAnalysisException("Could not find constructor: " + dataClass);

	}

	/**
	 * A Java record's canonical constructor among {@code constructors}, where it claims no {@link Profile}: it takes
	 * every component, so it binds the record's whole shape. Matched by parameter type, as
	 * {@link RecordComponentFinder} tells it apart.
	 *
	 * @return the constructor, or {@code null} where {@code dataClass} is no record or its canonical constructor is
	 *         not among them or claims a profile
	 */
	private static Constructor<?> unprofiledCanonical(Class<?> dataClass, Constructor<?>[] constructors) {
		if (!dataClass.isRecord()) {
			return null;
		}
		Class<?>[] components = Arrays.stream(dataClass.getRecordComponents()).map(RecordComponent::getType)
				.toArray(Class<?>[]::new);
		for (Constructor<?> constructor : constructors) {
			if (Arrays.equals(constructor.getParameterTypes(), components)
					&& constructor.getAnnotation(Profile.class) == null) {
				return constructor;
			}
		}
		return null;
	}

	/**
	 * {@code constructors} minus the ones claiming a {@link Profile}, for the fallback path. A constructor
	 * built for another profile is not a candidate for this one -- it binds a shape this context is not
	 * asking for.
	 *
	 * <p><b>An empty result is left empty when a profile was asked for</b>, so a class whose every
	 * constructor is profiled and none of them this one fails rather than binding whichever shape happens to
	 * be the only candidate. It would otherwise silently bind another version's fields. With no profile
	 * asked for there is nothing to be wrong about, so the constructors stand as they are.
	 */
	private static Constructor<?>[] unprofiled(Constructor<?>[] constructors, boolean profileAsked) {
		List<Constructor<?>> plain = new ArrayList<>();
		for (Constructor<?> constructor : constructors) {
			if (constructor.getAnnotation(Profile.class) == null) {
				plain.add(constructor);
			}
		}
		return plain.isEmpty() && !profileAsked ? constructors : plain.toArray(new Constructor<?>[0]);
	}
}
