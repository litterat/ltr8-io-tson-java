package io.ltr8.bind;

/**
 * A position declared as {@code io.ltr8.annotation.Annotated<T>} -- the value's own descriptor, plus the
 * knowledge that a reader must wrap what it produces together with the annotations written at that position.
 *
 * <p>Modelled as a {@link DataClass} rather than a flag on {@link DataClassField} so that every position
 * which resolves a descriptor gets it the same way: a record field, an array element, a tuple position and
 * either side of a map entry all ask the context for a descriptor, and all of them find this one. A flag
 * would have had to be added to each of those position models separately.
 *
 * <p>{@link #valueClass()} is the descriptor for {@code T} -- what actually reads and writes the value; this
 * type adds no encoding of its own, since the annotations it carries are not part of the value's own
 * representation but of the framing around it (§3.1's {@code *annotation [type-ref] core-value}).
 */
public final class DataClassAnnotated extends DataClass {

	private final DataClass valueClass;

	public DataClassAnnotated(Class<?> targetType, DataClass valueClass) {
		super(targetType, null);
		this.valueClass = valueClass;
	}

	/** The descriptor for {@code T}, the value inside the box. */
	public DataClass valueClass() {
		return valueClass;
	}

	@Override
	public String toString() {
		return "DataClassAnnotated [value=" + valueClass + "]";
	}
}
