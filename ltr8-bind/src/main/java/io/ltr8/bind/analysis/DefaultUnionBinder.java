package io.ltr8.bind.analysis;

import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassUnion;


import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class DefaultUnionBinder {

	public DefaultUnionBinder() {
	}

	public DataClassUnion resolveUnion(DataBindContext context, Class<?> targetClass, Type parameterizedType)
			throws DataBindException {
		if (targetClass.isSealed()) {
			List<Class<?>> unionMembers = new ArrayList<>();
			collectSealedMembers(targetClass, unionMembers);

			return new DataClassUnion(targetClass, unionMembers.toArray(new Class<?>[0]), false);
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

	/**
	 * Recurses into any permitted subclass that is itself sealed, flattening a multi-level sealed
	 * hierarchy down to its concrete leaves -- a permitted subclass must be sealed, non-sealed, or
	 * final (the compiler enforces this), so a member that isn't itself sealed is always a genuine
	 * leaf (a non-sealed permitted class may still gain further subclasses at runtime, unknown until
	 * then, so it's kept as-is rather than walked into). The hierarchy is guaranteed acyclic (a
	 * sealed type can never permit itself, directly or transitively), so this always terminates.
	 */
	private static void collectSealedMembers(Class<?> sealedClass, List<Class<?>> collected) {
		for (Class<?> member : sealedClass.getPermittedSubclasses()) {
			if (member.isSealed()) {
				collectSealedMembers(member, collected);
			} else {
				collected.add(member);
			}
		}
	}
}
