package io.ltr8.bind.analysis;

import io.ltr8.annotation.Atom;
import io.ltr8.annotation.ToData;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.analysis.ClassSignals.Signal;
import io.ltr8.bind.bridge.EnumStringBridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class AtomBinder {

	private static final String TODATA_METHOD = "toData";
	private static final String TOOBJECT_METHOD = "toObject";

	private static final String NO_WAY_IN = "an atom's way in is one public constructor or public static method marked "
			+ "@Atom, taking a value the wire carries";

	public AtomBinder() {
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

	/** A way out: the method an atom is written through, and the value it returns. */
	private record WayOut(Method method, Class<?> value, String described) {
	}

	/**
	 * The atom {@code signals}' class says it is, or {@code null} where it carries {@code @Atom} on neither itself, a
	 * way in nor a way out. A static way in is the class's own, since a super class's returns the super class; a way out
	 * may be inherited.
	 */
	public DataClassAtom resolveAtom(DataBindContext context, ClassSignals signals) throws CodeAnalysisException {
		Class<?> targetClass = signals.type();
		if (signals.has(Signal.PRIMITIVE)) {
			// Should not get here unless primitives not registered.
			throw new CodeAnalysisException("Primitive not registered: " + targetClass.getName());
		}

		List<Executable> waysIn = waysIn(targetClass);
		if (waysIn.isEmpty()) {
			if (signals.has(Signal.ATOM_ON_TYPE)) {
				throw refusal("%s carries @Atom and marks no constructor or static method @Atom: " + NO_WAY_IN,
						targetClass.getName());
			}
			List<WayOut> marked = markedWaysOut(targetClass);
			if (!marked.isEmpty()) {
				throw refusal("%s says a way out, %s, and marks no constructor or static method @Atom: " + NO_WAY_IN,
						targetClass.getName(), marked.getFirst().described());
			}
			return null;
		}
		if (waysIn.size() > 1) {
			throw refusal("%s marks two ways in @Atom, %s and %s: an atom has one", targetClass.getName(),
					describe(waysIn.get(0)), describe(waysIn.get(1)));
		}
		Executable wayIn = waysIn.getFirst();
		Class<?> value = wayIn.getParameterTypes()[0];

		List<WayOut> waysOut = waysOut(targetClass);
		if (waysOut.isEmpty()) {
			throw refusal("%s marks %s @Atom, its way in, and says no way out: mark the public instance method "
					+ "returning %s @Atom, or implement ToData<%s>", targetClass.getName(), describe(wayIn),
					value.getName(), box(value).getName());
		}
		if (waysOut.size() > 1) {
			throw refusal("%s says two ways out, %s and %s: an atom has one", targetClass.getName(),
					waysOut.get(0).described(), waysOut.get(1).described());
		}
		WayOut wayOut = waysOut.getFirst();
		if (box(value) != box(wayOut.value())) {
			throw refusal("%s's way in %s takes %s and its way out %s returns %s: both halves of an atom carry one "
					+ "value", targetClass.getName(), describe(wayIn), value.getName(), wayOut.described(),
					wayOut.value().getName());
		}

		MethodHandle toObject = wayIn instanceof Constructor<?> constructor
				? Members.constructor(targetClass, constructor)
				: Members.staticMethod(targetClass, (Method) wayIn);
		MethodHandle toData = Members.virtual(targetClass, wayOut.method())
				.asType(MethodType.methodType(value, targetClass));
		return new DataClassAtom(targetClass, new DataClassBridge(value, toData, toObject));
	}

	private static List<Executable> waysIn(Class<?> targetClass) throws CodeAnalysisException {
		List<Executable> waysIn = new ArrayList<>();
		for (Constructor<?> constructor : targetClass.getDeclaredConstructors()) {
			if (constructor.isAnnotationPresent(Atom.class)) {
				if (!takesOneWireValue(constructor)) {
					throw refusal("@Atom constructor of %s must take one value the wire carries -- a primitive, a box, "
							+ "a String or a BigInteger/BigDecimal -- and takes %s", targetClass.getName(),
							Arrays.toString(constructor.getParameterTypes()));
				}
				if (Modifier.isAbstract(targetClass.getModifiers())) {
					throw refusal("%s is abstract, so %s, marked @Atom, cannot build it: an abstract class's way in is "
							+ "a static method", targetClass.getName(), describe(constructor));
				}
				waysIn.add(constructor);
			}
		}
		for (Method method : targetClass.getDeclaredMethods()) {
			if (ClassSignals.isMarkedStatic(method)) {
				if (!takesOneWireValue(method)) {
					throw refusal("@Atom static factory %s.%s must take one value the wire carries -- a primitive, a "
							+ "box, a String or a BigInteger/BigDecimal -- and takes %s", targetClass.getName(),
							method.getName(), Arrays.toString(method.getParameterTypes()));
				}
				if (method.getReturnType() != targetClass) {
					throw refusal("%s marks %s @Atom, which returns %s: a static way in returns %1$s",
							targetClass.getName(), describe(method), method.getReturnType().getTypeName());
				}
				waysIn.add(method);
			}
		}
		waysIn.sort(Comparator.comparing(AtomBinder::describe));
		return waysIn;
	}

	private static boolean takesOneWireValue(Executable executable) {
		return executable.getParameterCount() == 1 && isWireScalar(executable.getParameterTypes()[0]);
	}

	/** The {@link #markedWaysOut}, and {@code targetClass}'s {@code ToData.toData()} where it is none of them. */
	private static List<WayOut> waysOut(Class<?> targetClass) throws CodeAnalysisException {
		List<WayOut> waysOut = markedWaysOut(targetClass);
		if (ToData.class.isAssignableFrom(targetClass)) {
			Type[] arguments = TypeArguments.arguments(targetClass, ToData.class);
			if (arguments == null) {
				throw refusal("%s implements ToData raw: a way out through ToData names the value it returns, "
						+ "ToData<S>", targetClass.getName());
			}
			if (!(arguments[0] instanceof Class<?> value)) {
				throw refusal("%s implements ToData<%s>: a way out through ToData names the value it returns by a "
						+ "class", targetClass.getName(), arguments[0].getTypeName());
			}
			if (waysOut.stream().noneMatch(wayOut -> wayOut.method().getName().equals(TODATA_METHOD))) {
				Method toData = toDataMethod(targetClass);
				waysOut.add(new WayOut(toData, value, describe(toData) + " of ToData<" + value.getName() + ">"));
			}
		}
		waysOut.sort(Comparator.comparing(WayOut::described));
		return waysOut;
	}

	/**
	 * The instance methods marked {@code @Atom} that {@code targetClass} declares or inherits from a super class, each
	 * once however often it is overridden. Bridge methods are skipped: javac copies a method's annotations onto its
	 * bridges.
	 */
	private static List<WayOut> markedWaysOut(Class<?> targetClass) throws CodeAnalysisException {
		List<WayOut> waysOut = new ArrayList<>();
		Set<String> marked = new HashSet<>();
		Map<TypeVariable<?>, Type> inherited = TypeArguments.inherited(targetClass);
		for (Class<?> declarer = targetClass; declarer != null; declarer = declarer.getSuperclass()) {
			for (Method method : declarer.getDeclaredMethods()) {
				if (Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic()
						|| !method.isAnnotationPresent(Atom.class)) {
					continue;
				}
				if (method.getParameterCount() != 0) {
					throw refusal("%s marks %s @Atom, which takes parameters: an instance method marked @Atom is the "
							+ "way out, and takes none", targetClass.getName(), describe(method));
				}
				if (marked.add(method.getName())) {
					Class<?> value = TypeArguments.erasure(
							TypeArguments.substitute(method.getGenericReturnType(), inherited));
					waysOut.add(new WayOut(method, value, describe(method) + " marked @Atom"));
				}
			}
		}
		waysOut.sort(Comparator.comparing(WayOut::described));
		return waysOut;
	}

	private static Method toDataMethod(Class<?> targetClass) {
		try {
			return targetClass.getMethod(TODATA_METHOD);
		} catch (NoSuchMethodException e) {
			// ToData declares toData() public, so every class implementing it has one.
			throw new IllegalStateException(e);
		}
	}

	private static Class<?> box(Class<?> type) {
		return MethodType.methodType(type).wrap().returnType();
	}

	private static String describe(Executable executable) {
		String parameters = Arrays.stream(executable.getParameterTypes()).map(Class::getTypeName)
				.collect(Collectors.joining(", "));
		String declarer = executable.getDeclaringClass().getName();
		return executable instanceof Constructor<?> ? "constructor " + declarer + "(" + parameters + ")"
				: "method " + declarer + "." + executable.getName() + "(" + parameters + ")";
	}

	private static CodeAnalysisException refusal(String format, Object... arguments) {
		return new CodeAnalysisException(String.format(format, arguments));
	}

	/** Binds a plain Java enum to its {@code name()} via {@link EnumStringBridge}. */
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
