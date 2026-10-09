package io.ltr8.bind.analysis;

import io.ltr8.annotation.Atom;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.bridge.EnumStringBridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Set;

public class DefaultAtomBinder {

	private static final String TODATA_METHOD = "toData";
	private static final String TOOBJECT_METHOD = "toObject";

	public DefaultAtomBinder() {
	}

	/**
	 * The non-primitive types the wire carries as one token, so an atom bridging to one needs nothing
	 * further to be readable or writable -- the boxes, {@link String}, and the two arbitrary-precision
	 * numbers.
	 *
	 * <p>Each is a value both encodings carry directly: a string, a number, a boolean. {@link BigInteger}
	 * and {@link BigDecimal} are the exact numeric tiers' own host types ([TSON-DATA] §5.3, [TSON-JSON]
	 * §5.3) and are registered as core atoms alongside the boxes, so a class wrapping one has the same
	 * standing as a class wrapping a {@code long}.
	 *
	 * <p>Membership is what the wire can carry, not what Java calls a primitive -- which is why this is
	 * {@link #isWireScalar} rather than the {@code isPrimitive} it was named when it held only the boxes.
	 * Leaving {@code String} out had made {@code @Atom} the one route to an atom that could not describe a
	 * string-valued one, where a bridge registered on the context always could.
	 */
	private static final Set<Class<?>> BOXED_SCALARS = Set.of(Boolean.class, Character.class, Byte.class,
			Short.class, Integer.class, Long.class, Float.class, Double.class, Void.class, String.class,
			BigInteger.class, BigDecimal.class);

	/**
	 * Whether {@code type} is a value the wire carries directly. Primitives answer for themselves; everything
	 * else is {@link #BOXED_SCALARS}.
	 */
	private static boolean isWireScalar(Class<?> type) {
		return type.isPrimitive() || BOXED_SCALARS.contains(type);
	}

	public DataClassAtom resolveAtom(DataBindContext context, Class<?> targetClass)
			throws CodeAnalysisException {
		DataClassAtom descriptor = null;

		try {
			if (targetClass.isPrimitive()) {
				// Should not get here unless primitives not registered.
				throw new CodeAnalysisException("Primitive not registered: " + targetClass.getName());
			}

			// Check for annotation on constructor.
			Constructor<?>[] constructors = targetClass.getConstructors();
			for (Constructor<?> constructor : constructors) {
				Atom atomAnnotation = constructor
						.getAnnotation(Atom.class);
				if (atomAnnotation != null) {
					Parameter[] params = constructor.getParameters();
					if (params.length != 1 || !isWireScalar(params[0].getType())) {
						throw new CodeAnalysisException(String.format(
								"@Atom constructor of %s must take one value the wire carries -- a primitive, a box, "
										+ "a String or a BigInteger/BigDecimal -- and takes %s",
								targetClass.getName(), Arrays.toString(constructor.getParameterTypes())));
					}

					Class<?> dataClass = params[0].getType();

					MethodHandle toObject = MethodHandles.lookup().unreflectConstructor(constructor);

					// TODO Should do additional checks. Is return type same as constructor type.
					// Also should check for ToData interface implementation or @Atom on specific
					// method as could be different ways to say which method is toData.
					Method toDataMethod = targetClass.getDeclaredMethod(TODATA_METHOD);

					MethodHandle toData = MethodHandles.lookup().unreflect(toDataMethod);

					DataClassBridge bridge = new DataClassBridge(dataClass, toData, toObject);
					descriptor = new DataClassAtom(targetClass, bridge);
					break;
				}
			}

			// This is to look at static methods
			Method[] methods = targetClass.getDeclaredMethods();
			for (Method method : methods) {

				Atom atomAnnotation = method
						.getAnnotation(Atom.class);
				if (Modifier.isStatic(method.getModifiers()) && atomAnnotation != null) {
					Parameter[] params = method.getParameters();
					if (params.length != 1 || !isWireScalar(params[0].getType())) {
						throw new CodeAnalysisException(String.format(
								"@Atom static factory %s.%s must take one value the wire carries -- a primitive, a box, "
										+ "a String or a BigInteger/BigDecimal -- and takes %s",
								targetClass.getName(), method.getName(),
								Arrays.toString(method.getParameterTypes())));
					}

					MethodHandle toObject = MethodHandles.publicLookup().unreflect(method);

					Class<?> param = params[0].getType();

					MethodHandle toData = null;
					// Requires an accessor with the same type.
					for (Method accessorMethod : methods) {

						Atom accessorAtom = accessorMethod
								.getAnnotation(Atom.class);
						if (accessorAtom != null && !Modifier.isStatic(accessorMethod.getModifiers())) {
							if (accessorMethod.getReturnType() != param) {
								throw new CodeAnalysisException(
										"Atom accessor method must have a single primitive value as same type as static constructor");
							}
							toData = MethodHandles.publicLookup().unreflect(accessorMethod);
							break;
						}

					}

					if (toData == null) {
						throw new CodeAnalysisException("Atom accessor @Atom annotation not found");
					}

					DataClassBridge bridge = new DataClassBridge(param, toData, toObject);
					descriptor = new DataClassAtom(targetClass, bridge);

				}
			}

		} catch (SecurityException | IllegalAccessException | NoSuchMethodException | CodeAnalysisException e) {
			throw new CodeAnalysisException("Failed to get atom descriptor", e);
		}

		return descriptor;
	}

	/**
	 * Binds a plain Java enum to its {@code name()} via {@link EnumStringBridge} -- the default,
	 * overridable representation {@link DefaultClassBinder} routes every enum to directly, no
	 * {@code @Atom} required (see its own comment on why: {@code Class#isEnum()} is as unambiguous
	 * as {@code Class#isRecord()}, so there's nothing for an annotation to disambiguate here that
	 * record/array auto-detection doesn't already handle the same way for their own JDK-recognizable
	 * shapes).
	 */
	public DataClassAtom resolveEnum(Class<?> targetClass) throws CodeAnalysisException {
		try {
			EnumStringBridge bridge = new EnumStringBridge(targetClass);

			MethodHandle toObject = MethodHandles.lookup()
					.findVirtual(EnumStringBridge.class, TOOBJECT_METHOD, MethodType.methodType(Enum.class, String.class))
					.bindTo(bridge).asType(MethodType.methodType(targetClass, String.class));
			MethodHandle toData = MethodHandles.lookup()
					.findVirtual(EnumStringBridge.class, TODATA_METHOD, MethodType.methodType(String.class, Enum.class))
					.bindTo(bridge).asType(MethodType.methodType(String.class, targetClass));

			DataClassBridge enumBridge = new DataClassBridge(String.class, toData, toObject);
			return new DataClassAtom(targetClass, enumBridge);
		} catch (IllegalAccessException | NoSuchMethodException e) {
			throw new CodeAnalysisException("Failed to get atom descriptor for enum " + targetClass, e);
		}
	}
}
