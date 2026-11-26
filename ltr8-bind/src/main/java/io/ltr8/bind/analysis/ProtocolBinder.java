/*
 * Copyright (c) 2026, Litterat Pty Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ltr8.bind.analysis;

import io.ltr8.annotation.ToData;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.stream.BaseStream;
import java.util.stream.Collectors;

/**
 * Reads an interface as the exchanges {@code DataProtocol} describes, refusing every method that is not one
 * request record or none, one described response or none, and a {@code throws} clause of bridged errors.
 */
public final class ProtocolBinder {

	/** What one exchange was analysed to. The request and response are {@code null} where absent. */
	public record Exchange(String name, DataClass request, DataClass response, List<DataClass> errors,
			MethodHandle invoker) {
	}

	/**
	 * Promise-typed returns, refused by rule and not for want of a descriptor: describing one as a response now
	 * would change what the exchange means the day async or streaming arrives as a handler type of its own.
	 * {@code OptionalInt} and its siblings stand with {@code Optional}.
	 */
	private static final List<Class<?>> REFUSED_RETURNS = List.of(Optional.class, OptionalInt.class,
			OptionalLong.class, OptionalDouble.class, Future.class, CompletionStage.class, Flow.Publisher.class,
			BaseStream.class);

	private static final MethodType INVOKER = MethodType.methodType(Object.class, Object.class, Object.class);

	/**
	 * The name {@code name} is one with, for {@code DataProtocol.exchange}: each {@code _} removed and the code
	 * point after it upper-cased. It runs from the data's spelling to Java's only; the reverse is not a function
	 * ({@code getHTTPStatus}), so nothing maps a Java name to a data name.
	 */
	public static String javaName(String name) {
		StringBuilder java = new StringBuilder(name.length());
		boolean upper = false;
		for (int at = 0; at < name.length(); ) {
			int codePoint = name.codePointAt(at);
			at += Character.charCount(codePoint);
			if (codePoint == '_') {
				upper = true;
				continue;
			}
			java.appendCodePoint(upper ? Character.toUpperCase(codePoint) : codePoint);
			upper = false;
		}
		return java.toString();
	}

	public List<Exchange> resolve(DataBindContext context, Class<?> type) throws DataBindException {
		if (!type.isInterface() || type.isAnnotation()) {
			throw new DataBindException(String.format("%s is %s: a protocol is read from an interface",
					type.getName(), kindOf(type)));
		}
		requireAccessible(type, type.getName());

		Map<String, Method> byName = new HashMap<>();
		Map<String, Method> byJavaName = new HashMap<>();
		List<Method> methods = new ArrayList<>();
		for (Method method : type.getMethods()) {
			// Default, static and bridge methods are not abstract, so this one test drops all three.
			if (!Modifier.isAbstract(method.getModifiers()) || isObjectMethod(method)) {
				continue;
			}
			Method sameName = byName.putIfAbsent(method.getName(), method);
			if (sameName != null) {
				throw new DataBindException(String.format(
						"%s has two abstract methods named %s, %s and %s: an exchange is found by its name, so a "
								+ "protocol has one method of each name", type.getName(), method.getName(),
						signature(sameName), signature(method)));
			}
			Method sameJavaName = byJavaName.putIfAbsent(javaName(method.getName()), method);
			if (sameJavaName != null) {
				throw new DataBindException(String.format(
						"%s has methods named %s and %s, which are one name with each _ removed and the character "
								+ "after it upper-cased: an exchange is found by its name, so a protocol has one "
								+ "method of each name", type.getName(), sameJavaName.getName(), method.getName()));
			}
			methods.add(method);
		}

		List<Exchange> exchanges = new ArrayList<>(methods.size());
		for (Method method : methods) {
			exchanges.add(exchange(context, type, method));
		}
		return exchanges;
	}

	private Exchange exchange(DataBindContext context, Class<?> type, Method method) throws DataBindException {
		String at = type.getName() + "." + method.getName();
		if (method.getTypeParameters().length > 0) {
			throw new DataBindException(String.format(
					"%s declares type parameters of its own, %s: an exchange's request and response are classes",
					at, Arrays.toString(method.getTypeParameters())));
		}

		DataClass request = request(context, at, method);
		DataClass response = response(context, at, method);
		List<DataClass> errors = errors(context, at, method);

		MethodHandle invoker;
		try {
			// Found on the interface given rather than unreflected from the declaring one: an inherited method's
			// declarer may be an interface the public lookup cannot reach, where the one given is public.
			invoker = MethodHandles.publicLookup().findVirtual(type, method.getName(),
					MethodType.methodType(method.getReturnType(), method.getParameterTypes()));
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(at + " is listed by getMethods() and not found by its own type", e);
		} catch (IllegalAccessException e) {
			throw new DataBindException(at + " is not accessible through the public lookup: " + e.getMessage(), e);
		}
		if (request == null) {
			invoker = MethodHandles.dropArguments(invoker, 1, Object.class);
		}
		return new Exchange(method.getName(), request, response, errors, invoker.asType(INVOKER));
	}

	private DataClass request(DataBindContext context, String at, Method method) throws DataBindException {
		Type[] parameters = method.getGenericParameterTypes();
		if (parameters.length == 0) {
			return null;
		}
		if (parameters.length > 1) {
			throw new DataBindException(String.format(
					"%s takes %d parameters, %s: an exchange takes one record or nothing", at, parameters.length,
					Arrays.stream(parameters).map(Type::getTypeName).collect(Collectors.joining(", "))));
		}
		Type parameter = parameters[0];
		requireNoTypeVariable(at, "request", parameter);
		DataClass request = describe(context, at, "request", method.getParameterTypes()[0], parameter);
		if (!(request instanceof DataClassRecord)) {
			throw new DataBindException(String.format(
					"%s takes %s, which describes as %s: an exchange's request is a record", at,
					parameter.getTypeName(), kindOf(request)));
		}
		return request;
	}

	private DataClass response(DataBindContext context, String at, Method method) throws DataBindException {
		Class<?> returned = method.getReturnType();
		if (returned == void.class) {
			return null;
		}
		if (returned == Void.class) {
			throw new DataBindException(String.format(
					"%s returns java.lang.Void, which has no value: an exchange with no response returns void", at));
		}
		Type generic = method.getGenericReturnType();
		requireNoTypeVariable(at, "response", generic);
		for (Class<?> refused : REFUSED_RETURNS) {
			if (refused.isAssignableFrom(returned)) {
				throw new DataBindException(String.format(
						"%s returns %s, a %s: a response is a value and not a promise of one", at,
						generic.getTypeName(), refused.getName()));
			}
		}
		return describe(context, at, "response", returned, generic);
	}

	private List<DataClass> errors(DataBindContext context, String at, Method method) throws DataBindException {
		List<Class<?>> declared = new ArrayList<>();
		for (Type type : method.getGenericExceptionTypes()) {
			if (!(type instanceof Class<?> thrown)) {
				throw new DataBindException(String.format(
						"%s throws %s, a type variable: a declared error is a class", at, type.getTypeName()));
			}
			if (!ToData.class.isAssignableFrom(thrown)) {
				throw new DataBindException(String.format(
						"%s throws %s, which does not implement ToData: a declared error bridges to its record, "
								+ "so declare implements ToData<S> on %2$s with a public constructor taking S",
						at, thrown.getName()));
			}
			for (Class<?> other : declared) {
				if (other.isAssignableFrom(thrown) || thrown.isAssignableFrom(other)) {
					Class<?> sub = other.isAssignableFrom(thrown) ? thrown : other;
					throw new DataBindException(String.format(
							"%s throws %s and %s, and %s is a subclass of %s: a thrown error is an instance of one "
									+ "declared error at most", at, other.getName(), thrown.getName(),
							sub.getName(), sub == thrown ? other.getName() : thrown.getName()));
				}
			}
			declared.add(thrown);
		}

		List<DataClass> errors = new ArrayList<>(declared.size());
		for (Class<?> thrown : declared) {
			DataClass error = describe(context, at, "error", thrown, thrown);
			if (!(error instanceof DataClassRecord) || error.bridge().isEmpty()) {
				// A registration on the context, an atom bridge say, takes precedence over the class's ToData.
				throw new DataBindException(String.format(
						"%s throws %s, which this context describes as %s: a declared error is a record bridged "
								+ "through ToData", at, thrown.getName(), kindOf(error)));
			}
			for (DataClass other : errors) {
				Class<?> otherClass = other.typeClass();
				if (other.dataClass() == error.dataClass()) {
					throw new DataBindException(String.format(
							"%s throws %s and %s, which both bridge to %s: a record read back builds one declared "
									+ "error at most", at, otherClass.getName(), thrown.getName(),
							error.dataClass().getName()));
				}
			}
			errors.add(error);
		}
		return List.copyOf(errors);
	}

	private static DataClass describe(DataBindContext context, String at, String role, Class<?> type,
			Type generic) throws DataBindException {
		for (Class<?> named : classesNamed(generic)) {
			requireAccessible(named, at + "'s " + role + " " + generic.getTypeName() + " names " + named.getName()
					+ ", which");
		}
		try {
			return context.getDescriptor(type, generic);
		} catch (DataBindException e) {
			throw new DataBindException(String.format("%s's %s %s cannot be described: %s", at, role,
					generic.getTypeName(), e.getMessage()), e);
		}
	}

	/**
	 * The handles come from the public lookup, so a class that lookup cannot reach is refused here, naming what
	 * is wrong, rather than surfacing as an {@code IllegalAccessException} from the record binder.
	 */
	private static void requireAccessible(Class<?> type, String subject) throws DataBindException {
		if (type.isPrimitive()) {
			return;
		}
		try {
			MethodHandles.publicLookup().accessClass(type);
		} catch (IllegalAccessException e) {
			Module module = type.getModule();
			String why = !Modifier.isPublic(type.getModifiers()) ? type.getName() + " is not public"
					: !module.isExported(type.getPackageName())
							? "its package " + type.getPackageName() + " is not exported by " + module
									+ " to every module"
							: e.getMessage();
			throw new DataBindException(String.format(
					"%s is not accessible through the public lookup, since %s: a protocol and every class it "
							+ "names are public, in a package exported to every module", subject, why), e);
		}
	}

	/** Each class {@code type} names, its own and its type arguments', so each can be checked for access. */
	private static Set<Class<?>> classesNamed(Type type) {
		Set<Class<?>> named = new LinkedHashSet<>();
		collect(type, named);
		return named;
	}

	private static void collect(Type type, Set<Class<?>> named) {
		switch (type) {
			case Class<?> c when c.isArray() -> collect(c.getComponentType(), named);
			case Class<?> c -> named.add(c);
			case ParameterizedType p -> {
				collect(p.getRawType(), named);
				for (Type argument : p.getActualTypeArguments()) {
					collect(argument, named);
				}
			}
			case GenericArrayType g -> collect(g.getGenericComponentType(), named);
			case WildcardType w -> {
				for (Type bound : w.getUpperBounds()) {
					collect(bound, named);
				}
				for (Type bound : w.getLowerBounds()) {
					collect(bound, named);
				}
			}
			default -> {
			}
		}
	}

	private static void requireNoTypeVariable(String at, String role, Type type) throws DataBindException {
		TypeVariable<?> variable = typeVariableIn(type);
		if (variable != null) {
			throw new DataBindException(String.format(
					"%s's %s %s names the type variable %s of %s: an exchange's %2$s is a class",
					at, role, type.getTypeName(), variable.getName(), declarerName(variable)));
		}
	}

	private static TypeVariable<?> typeVariableIn(Type type) {
		return switch (type) {
			case TypeVariable<?> variable -> variable;
			case ParameterizedType p -> {
				for (Type argument : p.getActualTypeArguments()) {
					TypeVariable<?> found = typeVariableIn(argument);
					if (found != null) {
						yield found;
					}
				}
				yield null;
			}
			case GenericArrayType g -> typeVariableIn(g.getGenericComponentType());
			case WildcardType w -> {
				for (Type bound : w.getUpperBounds()) {
					TypeVariable<?> found = typeVariableIn(bound);
					if (found != null) {
						yield found;
					}
				}
				for (Type bound : w.getLowerBounds()) {
					TypeVariable<?> found = typeVariableIn(bound);
					if (found != null) {
						yield found;
					}
				}
				yield null;
			}
			default -> null;
		};
	}

	private static String declarerName(TypeVariable<?> variable) {
		return switch (variable.getGenericDeclaration()) {
			case Class<?> c -> c.getName();
			case Method m -> m.getDeclaringClass().getName() + "." + m.getName();
			default -> variable.getGenericDeclaration().toString();
		};
	}

	/**
	 * By signature and wherever declared: an interface restating {@code toString()} lists it as its own abstract
	 * method, and a call of it is still answered as {@code Object}'s.
	 */
	private static boolean isObjectMethod(Method method) {
		Class<?>[] parameters = method.getParameterTypes();
		return switch (method.getName()) {
			case "equals" -> parameters.length == 1 && parameters[0] == Object.class;
			case "hashCode", "toString" -> parameters.length == 0;
			default -> false;
		};
	}

	private static String signature(Method method) {
		return method.getGenericReturnType().getTypeName() + " " + method.getDeclaringClass().getName() + "."
				+ method.getName() + "(" + Arrays.stream(method.getGenericParameterTypes()).map(Type::getTypeName)
				.collect(Collectors.joining(", ")) + ")";
	}

	private static String kindOf(Class<?> type) {
		if (type.isAnnotation()) {
			return "an annotation interface";
		}
		if (type.isEnum()) {
			return "an enum";
		}
		if (type.isRecord()) {
			return "a record";
		}
		if (type.isArray()) {
			return "an array class";
		}
		if (type.isPrimitive()) {
			return "a primitive type";
		}
		return "a class";
	}

	private static String kindOf(DataClass descriptor) {
		return switch (descriptor) {
			case DataClassRecord record when record.bridge().isEmpty() -> "a record with no bridge";
			case DataClassAtom atom when atom.bridge().isPresent() -> "an atom bridged to "
					+ atom.dataClass().getName();
			case DataClassAtom _ -> "an atom";
			case DataClassArray _ -> "an array";
			case DataClassMap _ -> "a map";
			case DataClassUnion _ -> "a union";
			case DataClassTuple _ -> "a tuple";
			case DataClassAnnotated _ -> "an annotated value";
			default -> descriptor.getClass().getSimpleName();
		};
	}
}
