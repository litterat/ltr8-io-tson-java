package io.ltr8.bind.analysis;


import io.ltr8.annotation.Atom;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Tuple;
import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;

public class DefaultClassBinder {


	// private final TypeContextNameBinder nameBinder;

	private final DefaultRecordBinder recordBinder;
	private final DefaultAtomBinder atomBinder;
	private final DefaultUnionBinder unionBinder;
	private final DefaultArrayBinder arrayBinder;
	private final DefaultMapBinder mapBinder;
	private final DefaultTupleBinder tupleBinder;


	public DefaultClassBinder() {

		recordBinder = new DefaultRecordBinder();
		atomBinder = new DefaultAtomBinder();
		unionBinder = new DefaultUnionBinder();
		arrayBinder = new DefaultArrayBinder();
		mapBinder = new DefaultMapBinder();
		tupleBinder = new DefaultTupleBinder();
	}

	public DataClass resolve(DataBindContext context,  Class<?> targetClass, Type parameterizedType)
			throws DataBindException {

		DataClass result = null;

		// If this is a schema first situation then this will return a definition and the job is to bind to
		// it. If it is a code first situation then we expect this to throw an exception, and we need to
		// first create the definition and then bind the class to that definition.

		if (isTuple(targetClass)) {
			// Checked ahead of isRecord: a genuine Java record annotated @Tuple would otherwise be
			// claimed by isRecord()'s own isRecord() check first.
			result = tupleBinder.resolveTuple(context, targetClass);
		} else if (isRecord(targetClass)) {
			result = recordBinder.resolveRecord(context, targetClass);
		} else if (isUnion(targetClass)) {
			result = unionBinder.resolveUnion(context, targetClass, parameterizedType);
		} else if (targetClass.isEnum()) {
			// A plain Java enum needs no annotation, the same as record/array -- Class#isEnum() is
			// exactly as unambiguous a JDK signal as Class#isRecord(). Binds via EnumStringBridge
			// (by name()); a caller wanting a different representation (by ordinal, by a custom
			// code) pre-registers their own bridge on the DataBindContext instead, the same
			// override mechanism every other auto-detected type already has -- an explicit
			// registration is cached and short-circuits this resolver entirely.
			result = atomBinder.resolveEnum(targetClass);
		} else if (isAtom(targetClass)) {
			result = atomBinder.resolveAtom(context, targetClass);
		} else if (isMap(targetClass)) {
			// Checked ahead of isArray: Map is not a Collection, so there's no auto-detection
			// collision between the two -- this ordering just keeps the more specific check first.
			result = mapBinder.resolveMap(context, targetClass, parameterizedType);
		} else if (isArray(targetClass)) {
			result = arrayBinder.resolveArray(context, targetClass, parameterizedType);
		} else {
			throw new DataBindException(
					String.format("Unable to find a valid data conversion for class: %s", targetClass));
		}

		return result;
	}


	private boolean isUnion(Class<?> targetClass) {
		if (Collection.class.isAssignableFrom(targetClass)) {
			return false;
		}

		// If the targetClass is using sealed interfaces then it provides the union type.
		if (targetClass.isSealed()) {
			return true;
		}

		if (targetClass.isInterface()) {

			// Interface needs to be marked with @Data or Serializable.
			Union unionAnnotation = targetClass
					.getAnnotation(Union.class);
			if (unionAnnotation != null) {
				return true;
			}

		} else

		if (Modifier.isAbstract(targetClass.getModifiers())) {
			// Array classes are abstract and we don't want them.
			if (targetClass.isArray()) {
				// this is classed as an array, not a union.
				return false;
			}

			// Interface needs to be marked with @Data or Serializable.
			Union unionAnnotation = targetClass
					.getAnnotation(Union.class);
			if (unionAnnotation != null) {
				return true;
			}

			return false;
		}

		return false;
	}

	private boolean isTuple(Class<?> targetClass) {
		return targetClass.getAnnotation(Tuple.class) != null;
	}

	private boolean isRecord(Class<?> targetClass) {

		// A genuine Java record needs no annotation -- RecordComponentFinder reads it directly.
		if (targetClass.isRecord()) {
			return true;
		}

		// A hand-written (pre-record, or simply not a record) immutable tuple class, identified
		// by @Record on the class, a constructor, or a static factory method -- ImmutableFinder
		// matches its constructor arguments to fields via bytecode analysis.
		Record recordAnnotation = targetClass.getAnnotation(Record.class);
		if (recordAnnotation != null) {
			return true;
		}

		Constructor<?>[] constructors = targetClass.getConstructors();
		for (Constructor<?> constructor : constructors) {
			recordAnnotation = constructor.getAnnotation(Record.class);
			if (recordAnnotation != null) {
				return true;
			}
		}

		Method[] methods = targetClass.getDeclaredMethods();
		for (Method method : methods) {
			recordAnnotation = method.getAnnotation(Record.class);
			if (Modifier.isStatic(method.getModifiers()) && recordAnnotation != null) {
				return true;
			}
		}

		// Class has implemented ToData so exports/imports a data class (which must itself
		// resolve to a real record or a hand-written one).
		return ToData.class.isAssignableFrom(targetClass);
    }

	private boolean isArray(Class<?> targetClass) {

		if (targetClass.isArray() || Collection.class.isAssignableFrom(targetClass)) {
			return true;
		}

		return false;
	}

	private boolean isMap(Class<?> targetClass) {
		return Map.class.isAssignableFrom(targetClass);
	}

	private boolean isAtom(Class<?> targetClass) {

		if (targetClass.isPrimitive()) {
			return true;
		}

		// Check for class annoation
		Atom atomAnnotation = targetClass
				.getAnnotation(Atom.class);
		if (atomAnnotation != null) {
			return true;
		}

		// Check for annotation on constructor.
		Constructor<?>[] constructors = targetClass.getConstructors();
		for (Constructor<?> constructor : constructors) {
			atomAnnotation = constructor.getAnnotation(Atom.class);
			if (atomAnnotation != null) {
				return true;
			}
		}

		// This is to look at static methods
		Method[] methods = targetClass.getDeclaredMethods();
		for (Method method : methods) {

			atomAnnotation = method.getAnnotation(Atom.class);
			if (Modifier.isStatic(method.getModifiers()) && atomAnnotation != null) {
				return true;
			}
		}

		return false;
	}

}
