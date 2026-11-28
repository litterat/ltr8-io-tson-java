package io.ltr8.bind.analysis;

import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassUnion;


import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class UnionBinder {

	private final Predicate<Class<?>> describedAsUnion;

	/** {@code describedAsUnion} answers whether a class's own descriptor is a union. */
	public UnionBinder(Predicate<Class<?>> describedAsUnion) {
		this.describedAsUnion = describedAsUnion;
	}

	public DataClassUnion resolveUnion(DataBindContext context, Class<?> targetClass, Type parameterizedType)
			throws DataBindException {
		String written = "is a union, written as one of its members";
		ComponentAnnotations.refuseUnread(targetClass, written);
		ComponentAnnotations.refuseAtom(targetClass, written);
		if (targetClass.isSealed()) {
			List<Class<?>> unionMembers = new ArrayList<>();
			collectSealedMembers(targetClass, unionMembers);

			// A member is final, sealed or non-sealed. Only a non-sealed one stands for classes it cannot list
			// (DataClassUnion#isMemberType), so a hierarchy with none admits nothing it does not list.
			boolean sealed = unionMembers.stream()
					.allMatch(member -> Modifier.isFinal(member.getModifiers()) || member.isSealed());
			return new DataClassUnion(targetClass, unionMembers.toArray(new Class<?>[0]), sealed);
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
	 * A permitted class is a member where an instance of it can be written as itself, and a sealed one's
	 * permitted classes are members too. An enum is a member and is not walked into: the JDK seals an enum with
	 * a constant body, and the class it permits is that constant's, which is written as the enum. A concrete
	 * sealed class whose own descriptor is a union is no member: an instance of it has no descriptor but the
	 * union, which names its members and cannot write itself as one. A non-sealed class is kept rather than
	 * walked into, since what extends it is unknown until it is met.
	 */
	private void collectSealedMembers(Class<?> sealedClass, List<Class<?>> collected) {
		for (Class<?> member : sealedClass.getPermittedSubclasses()) {
			if (!member.isSealed() || member.isEnum()) {
				collected.add(member);
				continue;
			}
			if (!Modifier.isAbstract(member.getModifiers()) && !describedAsUnion.test(member)) {
				collected.add(member);
			}
			collectSealedMembers(member, collected);
		}
	}
}
