package io.ltr8.bind.analysis;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassTuple;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

/**
 * Resolves an {@code @Tuple}-annotated genuine Java {@code record} into a {@link DataClassTuple}.
 * Deliberately narrower than {@link DefaultRecordBinder}: no setters, no {@code Optional}
 * unwrapping, no bridges, no unions -- a record used as a tuple always has exactly its canonical
 * constructor and its {@link RecordComponent}s, so there's nothing here that {@link
 * RecordComponentFinder}'s fuller {@code ComponentInfo}/{@code DataClassField} machinery would add
 * beyond what {@code Class#getRecordComponents()} already gives directly.
 */
public class DefaultTupleBinder {

	public DefaultTupleBinder() {

	}

	public DataClassTuple resolveTuple(DataBindContext context, Class<?> targetClass) throws DataBindException {
		if (!targetClass.isRecord()) {
			// @Tuple only targets ElementType.TYPE, so DefaultClassBinder only reaches here for
			// classes actually carrying the annotation -- this is a misuse guard, not a routing
			// check: only a genuine Java record has record components / a canonical constructor
			// to resolve positionally in the first place.
			throw new CodeAnalysisException("@Tuple can only be applied to a genuine Java record: " + targetClass);
		}

		RecordComponent[] components = targetClass.getRecordComponents();
		MethodHandles.Lookup lookup = MethodHandles.lookup();

		try {
			DataClassElement[] elements = new DataClassElement[components.length];
			Class<?>[] componentTypes = new Class<?>[components.length];
			for (int i = 0; i < components.length; i++) {
				RecordComponent component = components[i];
				componentTypes[i] = component.getType();

				DataClass elementDataClass = context.getDescriptor(component.getType(), component.getGenericType());
				MethodHandle accessor = lookup.unreflect(component.getAccessor());

				elements[i] = new DataClassElement(i, elementDataClass, accessor);
			}

			// The canonical constructor's parameter types are exactly the record components' own
			// types, in order -- getDeclaredConstructor(componentTypes) finds it unambiguously
			// even if the record also declares other (non-canonical) constructors.
			Constructor<?> canonical = targetClass.getDeclaredConstructor(componentTypes);
			MethodHandle rawConstructor = lookup.unreflectConstructor(canonical);
			MethodHandle constructor = rawConstructor.asSpreader(Object[].class, components.length);

			return new DataClassTuple(targetClass, constructor, elements);

		} catch (IllegalAccessException | NoSuchMethodException | SecurityException e) {
			throw new CodeAnalysisException("Failed to get tuple descriptor for " + targetClass, e);
		}
	}

}
