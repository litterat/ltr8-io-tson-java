package io.ltr8.bind.analysis;

import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassUnion;


import java.lang.reflect.Type;

public class DefaultUnionBinder {

	public DefaultUnionBinder() {
	}

	public DataClassUnion resolveUnion(DataBindContext context, Class<?> targetClass, Type parameterizedType)
			throws DataBindException {
		if (targetClass.isSealed()) {
			Class<?>[] members = targetClass.getPermittedSubclasses();

			Class<?>[] unionMembers = new Class[members.length];
			for (int x = 0; x < members.length; x++) {
				Class<?> memberClass = members[x];
				unionMembers[x] = memberClass;
			}

			return new DataClassUnion(targetClass, unionMembers, false);
		}

		Union unionAnnotation = targetClass
				.getAnnotation(Union.class);
		if (unionAnnotation != null) {
			if (unionAnnotation.value() != null && unionAnnotation.value().length > 0) {

				Class<?>[] unionMembers = new Class[unionAnnotation.value().length];
				for (int x = 0; x < unionAnnotation.value().length; x++) {
					Class<?> memberClass = unionAnnotation.value()[x];

					// The members of the union are not resolved at this point as we
					// can end up in an infinite loop. By using the actual member classes
					// the resolution loop is broken.
					unionMembers[x] = memberClass;
				}

				return new DataClassUnion(targetClass, unionMembers, unionAnnotation.sealed());

			} else {
				return new DataClassUnion(targetClass,  new Class[0], false);
			}
		}

		// Might need to look for sealed classes/interfaces here.
		throw new DataBindException("Invalid union");
	}


}
