package io.ltr8.bind.analysis;


import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.analysis.ClassSignals.Signal;

import io.ltr8.annotation.Annotated;
import io.ltr8.bind.DataClassAnnotated;
import java.lang.reflect.ParameterizedType;
import io.ltr8.annotation.Annotations;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Type;
import java.util.function.Supplier;

public class ClassBinder {


	// private final TypeContextNameBinder nameBinder;

	private final RecordBinder recordBinder;
	private final AtomBinder atomBinder;
	private final UnionBinder unionBinder;
	private final ArrayBinder arrayBinder;
	private final MapBinder mapBinder;
	private final TupleBinder tupleBinder;
	private final TransparentBinder transparentBinder;


	public ClassBinder() {

		recordBinder = new RecordBinder();
		atomBinder = new AtomBinder();
		unionBinder = new UnionBinder();
		arrayBinder = new ArrayBinder();
		mapBinder = new MapBinder();
		tupleBinder = new TupleBinder();
		transparentBinder = new TransparentBinder();
	}

	/**
	 * {@code value} presented as a boxed position -- an annotated map's key, which carries its own metadata.
	 *
	 * <p>Takes and yields a source rather than a descriptor, because the key it boxes may sit on a cycle in
	 * the type graph: the box is built when the key it wraps can be resolved, and not before.
	 */
	static Supplier<DataClass> boxed(Supplier<DataClass> value) throws DataBindException {
		// The box is built now; only the key it wraps may be deferred, and the box holds that as a source
		// exactly as any other holder does.
		DataClass box = annotatedHandles(Annotated.class, value);
		return () -> box;
	}

	/** {@code Annotated<T>}'s descriptor, with the handles a reader builds and reads a box through. */
	private static DataClass annotatedDescriptor(DataBindContext context, Class<?> targetClass,
			Type parameterizedType) throws DataBindException {
		return annotatedHandles(targetClass, annotatedValueDescriptor(context, parameterizedType));
	}

	private static DataClass annotatedHandles(Class<?> targetClass, Supplier<DataClass> valueDescriptor)
			throws DataBindException {
		try {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			MethodHandle constructor = lookup.findConstructor(Annotated.class,
					MethodType.methodType(void.class, Object.class, Annotations.class));
			MethodHandle value = lookup.findVirtual(Annotated.class, "value",
					MethodType.methodType(Object.class));
			MethodHandle annotations = lookup.findVirtual(Annotated.class, "annotations",
					MethodType.methodType(Annotations.class));
			return new DataClassAnnotated(targetClass, valueDescriptor, constructor, value, annotations);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new CodeAnalysisException("Failed to get Annotated descriptor", e);
		}
	}

	/**
	 * The descriptor for {@code T} in {@code Annotated<T>}, taken from the declared type argument. A raw
	 * {@code Annotated} has none to take, and is rejected rather than guessed at -- the box exists to carry a
	 * value of a known type, so an unparameterized one has nothing to read.
	 */
	private static Supplier<DataClass> annotatedValueDescriptor(DataBindContext context, Type parameterizedType)
			throws DataBindException {
		if (!(parameterizedType instanceof ParameterizedType parameterized)) {
			throw new DataBindException("Annotated must be declared with its value type (Annotated<T>), found a raw "
					+ parameterizedType);
		}
		Type argument = parameterized.getActualTypeArguments()[0];
		if (!TypeArguments.isHeld(argument)) {
			throw new DataBindException("Annotated must be declared with a class, a parameterized type or an array of "
					+ "one as its value type, found " + parameterized.getTypeName());
		}
		return context.componentSource(TypeArguments.erasure(argument), argument);
	}

	/**
	 * The atom form {@code targetClass} declares for itself, or {@code null} where it declares none.
	 *
	 * <p><b>What makes an atom an atom is a bridge to a type the wire can carry</b>, and the two routes a
	 * class has for stating one are the two consulted here: a plain Java enum, whose representation is
	 * settled by {@link AtomBinder#resolveEnum}, and the way in and way out it marks, read by
	 * {@link AtomBinder#resolveAtom}. Both end in a {@link DataClassBridge} naming the wire type and the crossing
	 * back; a class carrying {@code @Atom} that says either badly is refused here as it is by {@link #resolve}.
	 *
	 * <p>Exposed for {@code DataBindContext.registerAtom}, so that registering a class as an atom finds the
	 * form it already declares rather than replacing it with one that states nothing. Its kind is
	 * {@link Kind#asAtom}, not {@link #resolve}'s.
	 *
	 * <p>A primitive is not asked -- {@link AtomBinder} refuses one outright, on the grounds that
	 * reaching it means the primitives were never registered.
	 */
	public DataClassAtom atomForm(DataBindContext context, Class<?> targetClass) throws CodeAnalysisException {
		ClassSignals signals = ClassSignals.of(targetClass);
		refuseBlankTypename(signals);
		return switch (Kind.asAtom(signals.present())) {
			case ENUM -> atomBinder.resolveEnum(targetClass);
			// A way out marked with no way in is refused, though it makes no class an atom.
			case ATOM, NONE -> signals.has(Signal.PRIMITIVE) ? null : atomBinder.resolveAtom(context, signals);
			default -> throw new IllegalStateException(targetClass.getName() + " has no atom form's kind");
		};
	}

	/** The form {@code DataBindContext.registerAtom} gives {@code targetClass} through {@code bridge}. */
	public DataClassBridge atomBridge(Class<?> targetClass, DataBridge<?, ?> bridge) throws CodeAnalysisException {
		refuseBlankTypename(targetClass);
		return RecordFields.atomBridge(targetClass, bridge);
	}

	/**
	 * Refuses a class whose {@link Typename} is blank, which declares no name and is not the absence of one. Every
	 * class a descriptor describes passes here first: through {@link #resolve}, a registration, or an embedded union.
	 */
	static void refuseBlankTypename(Class<?> targetClass) throws CodeAnalysisException {
		refuseBlankTypename(targetClass, targetClass.getAnnotation(Typename.class));
	}

	private static void refuseBlankTypename(ClassSignals signals) throws CodeAnalysisException {
		refuseBlankTypename(signals.type(), signals.typename());
	}

	private static void refuseBlankTypename(Class<?> targetClass, Typename typename) throws CodeAnalysisException {
		if (typename != null && typename.name().isBlank()) {
			throw new CodeAnalysisException(String.format("%s carries @Typename(name = \"%s\"), which is blank",
					targetClass.getName(), typename.name()));
		}
	}

	public DataClass resolve(DataBindContext context, Class<?> targetClass, Type parameterizedType)
			throws DataBindException {
		ClassSignals signals = ClassSignals.of(targetClass);
		refuseBlankTypename(signals);
		return switch (signals.kind()) {
			case ANNOTATED -> annotatedDescriptor(context, targetClass, parameterizedType);
			case TRANSPARENT -> transparentBinder.resolve(context, signals);
			case TUPLE -> tupleBinder.resolveTuple(context, signals);
			case RECORD -> recordBinder.resolveRecord(context, targetClass, parameterizedType);
			case BRIDGED -> recordBinder.resolveBridged(context, signals);
			// Another representation of an enum is a bridge registered on the context, cached ahead of this.
			case ENUM -> atomBinder.resolveEnum(targetClass);
			case UNION -> unionBinder.resolveUnion(signals);
			case ATOM -> atomBinder.resolveAtom(context, signals);
			case MAP -> mapBinder.resolveMap(context, targetClass, parameterizedType);
			case ARRAY -> arrayBinder.resolveArray(context, signals, parameterizedType);
			case NONE -> throw new DataBindException(
					String.format("Unable to find a valid data conversion for class: %s", targetClass));
		};
	}

}
