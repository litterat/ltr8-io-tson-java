package io.ltr8.bind.analysis;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.analysis.ClassSignals.Signal;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.function.Supplier;

/**
 * Resolves an {@code @Tuple}-annotated genuine Java {@code record} into a {@link DataClassTuple}.
 * Deliberately narrower than {@link RecordBinder}: no setters, no {@code Optional}
 * unwrapping, no bridges, no unions -- a record used as a tuple always has exactly its canonical
 * constructor and its {@link RecordComponent}s, so there's nothing here that {@link
 * RecordComponentFinder}'s fuller {@code ComponentInfo}/{@code DataClassField} machinery would add
 * beyond what {@code Class#getRecordComponents()} already gives directly.
 */
public class TupleBinder {

	public TupleBinder() {

	}

	public DataClassTuple resolveTuple(DataBindContext context, ClassSignals signals) throws DataBindException {
		Class<?> targetClass = signals.type();
		if (!signals.has(Signal.JDK_RECORD)) {
			throw new CodeAnalysisException("@Tuple can only be applied to a genuine Java record: " + targetClass);
		}
		if (signals.fieldOrder() != null) {
			throw new CodeAnalysisException(targetClass.getName() + " carries @FieldOrder, but is a @Tuple, whose "
					+ "elements are its record components in declared order; an order applies only to a class "
					+ "described by its own components");
		}

		ComponentAnnotations.refuseAny(targetClass, "is a @Tuple, written as its components by position");

		RecordComponent[] components = targetClass.getRecordComponents();

		try {
			DataClassElement[] elements = new DataClassElement[components.length];
			Class<?>[] componentTypes = new Class<?>[components.length];
			for (int i = 0; i < components.length; i++) {
				RecordComponent component = components[i];
				componentTypes[i] = component.getType();

				Supplier<DataClass> elementDataClass =
						context.componentSource(component.getType(), component.getGenericType());
				MethodHandle accessor;
				try {
					accessor = Members.virtual(targetClass, component.getAccessor());
				} catch (CodeAnalysisException e) {
					throw refused(targetClass, e);
				}

				elements[i] = new DataClassElement(i, elementDataClass, accessor);
			}

			// The canonical constructor's parameter types are exactly the record components' own
			// types, in order -- getDeclaredConstructor(componentTypes) finds it unambiguously
			// even if the record also declares other (non-canonical) constructors.
			Constructor<?> canonical = targetClass.getDeclaredConstructor(componentTypes);
			MethodHandle rawConstructor;
			try {
				rawConstructor = Members.constructor(targetClass, canonical);
			} catch (CodeAnalysisException e) {
				throw refused(targetClass, e);
			}
			MethodHandle constructor = rawConstructor.asSpreader(Object[].class, components.length);

			return new DataClassTuple(targetClass, constructor, elements);

		} catch (NoSuchMethodException | SecurityException e) {
			// Not every CodeAnalysisException: an element's own refusal, from componentSource, passes up unwrapped.
			throw refused(targetClass, e);
		}
	}

	private static CodeAnalysisException refused(Class<?> targetClass, Exception cause) {
		return new CodeAnalysisException("Failed to get tuple descriptor for " + targetClass, cause);
	}

}
