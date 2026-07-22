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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class DefaultAtomBinder {

	private static final String TODATA_METHOD = "toData";
	private static final String TOOBJECT_METHOD = "toObject";

	public DefaultAtomBinder() {
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static final Set<Class> WRAPPER_TYPES = new HashSet(Arrays.asList(Boolean.class, Character.class,
			Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class, Void.class));

	private boolean isPrimitive(Class<?> targetClass) {
        return targetClass.isPrimitive() || WRAPPER_TYPES.contains(targetClass);
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
					if (params.length != 1 || !isPrimitive(params[0].getType())) {
						throw new CodeAnalysisException(
								String.format("Atom must have single primitive argument", targetClass));
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
					if (params.length != 1 || !isPrimitive(params[0].getType())) {
						throw new CodeAnalysisException("Atom static method must have a single primitive value");
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

			// Allow enums to be serialized to their String value if using default
			// serialization.
			Atom enumAtom = targetClass
					.getAnnotation(Atom.class);
			if (targetClass.isEnum() && enumAtom != null) {

				EnumStringBridge bridge = new EnumStringBridge(targetClass);

				MethodHandle toObject = MethodHandles.lookup()
						.findVirtual(EnumStringBridge.class, TOOBJECT_METHOD, MethodType.methodType(Enum.class, String.class))
						.bindTo(bridge).asType(MethodType.methodType(targetClass, String.class));
				MethodHandle toData = MethodHandles.lookup()
						.findVirtual(EnumStringBridge.class, TODATA_METHOD, MethodType.methodType(String.class, Enum.class))
						.bindTo(bridge).asType(MethodType.methodType(String.class, targetClass));

				DataClassBridge enumBridge = new DataClassBridge(String.class, toData, toObject);
				descriptor = new DataClassAtom(targetClass, enumBridge);

			}
		} catch (SecurityException | IllegalAccessException | NoSuchMethodException | CodeAnalysisException e) {
			throw new CodeAnalysisException("Failed to get atom descriptor", e);
		}

		return descriptor;
	}
}
