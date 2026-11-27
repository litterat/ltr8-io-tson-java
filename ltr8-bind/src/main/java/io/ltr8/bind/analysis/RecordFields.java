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

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Each component's {@link DataClassField}, by what the component is: the {@link Annotations} carrier, a field with a
 * {@code @Field} bridge, an {@code Optional}, an {@code OptionalInt}, {@code OptionalLong} or {@code OptionalDouble},
 * a union, or a plain value.
 */
final class RecordFields {

	private static final String TODATA_METHOD = "toData";
	private static final String TOOBJECT_METHOD = "toObject";

	private RecordFields() {
	}

	private static MethodHandle accessor(ComponentInfo info) {
		return info.getReadMethod();
	}

	private static MethodHandle setter(ComponentInfo info) {
		MethodHandle setter = null;
		if (info.getWriteMethod() != null) {
			setter = info.getWriteMethod();
		}
		return setter;
	}

	private static boolean isRequired(ComponentInfo info) {
		Field fieldAnnotation = info.getField();
		boolean isRequired = false;
		if (fieldAnnotation != null) {
			isRequired = fieldAnnotation.required();
		}
		return isRequired;
	}

	static String fieldName(ComponentInfo info) {
		Field fieldAnnotation = info.getField();

		String fieldName = info.getName();
		if (fieldAnnotation != null) {

			if (fieldAnnotation.value() != null && !fieldAnnotation.value().isBlank()) {
				fieldName = fieldAnnotation.value();
			}
		}

		return fieldName;
	}

	/**
	 * Returns the DataClassField for the targetClass. Depending on the type of field build the DataClassField:
	 * - DataBridge - use the DataBridge as part of toData/toObject
	 * - Optional - use isPresent and unwrap/wrap for toData/toObject
	 * - OptionalInt, OptionalLong, OptionalDouble - use isPresent and unwrap/wrap.
	 * - Union -
	 * @param context DataBindContext that the DataClassRecord is being resoled. Recursively find member DataClass.
	 * @param targetClass target class for that the field is being found.
	 * @param info The ComponentInfo for the field that is being created.
	 * @param bridge The field's {@code @Field} bridge, or {@code null} where it names none.
	 * @param index The field index
	 * @return DataClassField for the ComponentField of the record.
	 */
	static DataClassField resolveField(DataBindContext context, Class<?> targetClass, ComponentInfo info,
			FieldBridge bridge, int index) throws NoSuchMethodException, IllegalAccessException, DataBindException {

		DataClassField component;

		if (info.getType() == Annotations.class) {
			// The declared type is the opt-in: not bound from an authored value at all, so nothing
			// here needs the usual context.getDescriptor() recursion. See resolveAnnotationsCarrierField.
			component = resolveAnnotationsCarrierField(targetClass, info, index);
		} else if (bridge != null) {
			component = resolveBridgeField(context, targetClass, info, bridge, index);
		} else if (optionalArgument(info) != null) {
			// If the input is Optional wrap the isPresent
			component = resolveOptionalField(context, targetClass, info, index);
		} else if (info.getType() == OptionalInt.class
				|| info.getType() == OptionalLong.class | info.getType() == OptionalDouble.class) {
			// Similar to Optional, use specific methods for isPresent
			component = resolveOptionalNumberField(context, targetClass, info, index);
		} else if (info.getUnion() != null) {
			//
			component = resolveUnionField(context, targetClass, info.getUnion(), info, index);
		} else {
			// Use basic information to build the DataClassField
			component = resolveSimpleField(context, targetClass, info, index);
		}

		return component;
	}

	/**
	 * Builds the {@code DataClassField} for a component whose declared type is {@link Annotations}.
	 * Deliberately skips {@code context.getDescriptor()} entirely -- unlike every other field kind, this one
	 * is never bound from an authored value at all (a reader populates it from the *enclosing* value's own
	 * wire-format annotations instead), so there is no {@code DataClass} to resolve and {@link
	 * DataClassField#dataClass()} is left {@code null}.
	 *
	 * <p>The field is reported through {@link DataClassRecord#annotationsCarrier()} rather than a flag on the
	 * field itself, so a reader can ask once, up front, instead of testing every field as it walks.
	 */
	private static DataClassField resolveAnnotationsCarrierField(Class<?> targetClass, ComponentInfo info, int index) {
		MethodHandle accessor = accessor(info);
		String fieldName = fieldName(info);

		MethodHandle isPresent = MethodHandles.dropArguments(
				MethodHandles.constant(boolean.class, true), 0, targetClass);

		return new DataClassField(index, fieldName, info.getType(), (DataClass) null, true, isPresent,
				accessor, null);
	}

	/**
	 * A simple field is the last option for resolveField and assumes a known atomic or compound type.
	 *
	 * @param context The TypeContext that maps the TypeLibrary to the DataClass.
	 * @param targetClass The target record class that the field is a member.
	 * @param info ComponentInfo is the information collected about the field.
	 * @param index The field number in the record.
	 * @return the DataClassField object for the field.
	 */
	private static DataClassField resolveSimpleField(DataBindContext context, Class<?> targetClass, ComponentInfo info,
			int index) throws DataBindException, NoSuchMethodException, IllegalAccessException {
		Supplier<DataClass> dataClass = context.componentSource(info.getType(), info.getGenericType());

		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);
		boolean isRequired = isRequired(info);
		String fieldName = fieldName(info);

		// Asked of the component's own declared type rather than of its descriptor: the descriptor may be
		// deferred on a cyclic edge, and pulling it here would resolve it in the middle of the resolution
		// that deferred it.
		if (info.getType().isPrimitive()) {
			isRequired = true;
		}

		// Make isPresent return true for primitives/isRequired or call Objects.nonNull(value)
		MethodHandle isPresent = null;
		if (info.getType().isPrimitive()) {
			isPresent = MethodHandles.constant(boolean.class, true);

			isPresent = MethodHandles.dropArguments(isPresent, 0, targetClass);
		} else {
			isPresent = MethodHandles.publicLookup()
					.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
					.asType(MethodType.methodType(boolean.class, info.getType()));

			isPresent = MethodHandles.filterArguments(isPresent, 0, accessor);
		}

		return new DataClassField(index, fieldName, info.getType(), dataClass, isRequired, isPresent, accessor, setter);
	}

	private static DataClassField resolveUnionField(DataBindContext context, Class<?> targetClass,
											 Union union, ComponentInfo info, int index)
			throws NoSuchMethodException, IllegalAccessException, DataBindException {
		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);
		boolean isRequired = isRequired(info);
		String fieldName = fieldName(info);

		Class<?> unionClass = info.getType();

		if (union.value() == null || union.value().length == 0) {
			throw new CodeAnalysisException("Union annotation must have one or more classes");
		}


		Class<?>[] unionTypes = new Class[union.value().length];
		for (int z = 0; z < union.value().length; z++) {
			Class<?> clss = union.value()[z];
			if (!unionClass.isAssignableFrom(clss)) {
				throw new CodeAnalysisException("Union types not assignable from class type");
			}

			unionTypes[z] = clss;

			/*
			 * if (unionTypes[z] instanceof DataClassUnion || unionTypes[z] instanceof DataClassArray) {
			 * Embedded unions will require more work to decide on how valid other unions or arrays would be.
			 * Unions with unknown type sets is problematic. If another union member set is known it might be
			 * ok to add all children to the parent. Something for future. throw new DataBindException(
			 * "Embedded union types can not include other unions or arrays"); }
			 */
		}

		DataClassUnion dataUnion = new DataClassUnion(unionClass, unionTypes,
				union.sealed());

		MethodHandle isPresent = MethodHandles.publicLookup()
				.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
				.asType(MethodType.methodType(boolean.class, info.getType()));

		MethodHandle unionCheck = MethodHandles.publicLookup()
				.findVirtual(dataUnion.getClass(), "checkIsMember", MethodType.methodType(Object.class, Object.class))
				.bindTo(dataUnion);

		MethodHandle accessorFilter = MethodHandles.filterReturnValue(accessor, unionCheck);

		isPresent = MethodHandles.filterArguments(isPresent, 0, accessor);

		return new DataClassField(index, fieldName, unionClass, dataUnion, isRequired, isPresent, accessorFilter,
				setter);
	}

	private static DataClassField resolveBridgeField(DataBindContext context, Class<?> targetClass, ComponentInfo info,
			FieldBridge bridge, int index) throws DataBindException, IllegalAccessException, NoSuchMethodException {

		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);
		boolean isRequired = isRequired(info);
		String fieldName = fieldName(info);

		Supplier<DataClass> tupleData = context.componentSource(bridge.dataClass(), bridge.dataType());

		accessor = MethodHandles.collectArguments(bridge.toData(), 0,
				accessor.asType(accessor.type().changeReturnType(info.getType())));

		if (setter != null) {
			setter = MethodHandles.filterArguments(setter, 1, bridge.toObject());
		}

		// Use Objects.nonNull(accessor()) to check if value is present.
		// Probably not hugely efficient.
		MethodHandle isPresent = MethodHandles.publicLookup()
				.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
				.asType(MethodType.methodType(boolean.class, bridge.dataClass()));

		isPresent = MethodHandles.filterArguments(isPresent, 0, accessor);

		return new DataClassField(index, fieldName, bridge.dataClass(), tupleData, isRequired, isPresent, accessor,
				setter);
	}

	/**
	 * A component's {@code @Field} bridge: {@code toData} takes the field's type to {@code dataClass}, {@code
	 * toObject} the reverse, both bound to one instance.
	 */
	record FieldBridge(Class<?> dataClass, Type dataType, MethodHandle toData, MethodHandle toObject) {
	}

	/**
	 * The bridge {@code info}'s {@code @Field} names, or {@code null} where it names none. Built once per component
	 * and shared by the field's accessor, its setter and the constructor, so a bridge holding state sees every call
	 * made for its field.
	 */
	static FieldBridge fieldBridge(Class<?> targetClass, ComponentInfo info)
			throws CodeAnalysisException, NoSuchMethodException, IllegalAccessException {
		Field fieldAnnotation = info.getField();
		if (fieldAnnotation == null || fieldAnnotation.bridge() == null
				|| fieldAnnotation.bridge() == DataBridge.class) {
			return null;
		}

		@SuppressWarnings("rawtypes")
		Class<? extends DataBridge> bridgeClass = fieldAnnotation.bridge();

		Type[] bridgeTypes = dataBridgeArguments(bridgeClass, Map.of());
		String bridgeOf = String.format("%s, the @Field bridge of %s's field '%s', ", bridgeClass.getName(),
				targetClass.getName(), info.getName());
		String converts = ": a field bridge names the classes it converts between, DataBridge<D, O>";
		if (bridgeTypes == null) {
			throw new CodeAnalysisException(bridgeOf + "reaches DataBridge raw" + converts);
		}
		boolean dataOpen = isOpen(bridgeTypes[0]);
		boolean objectOpen = isOpen(bridgeTypes[1]);
		if (dataOpen || objectOpen) {
			throw new CodeAnalysisException(String.format("%simplements DataBridge<%s, %s>, leaving its %s type open%s",
					bridgeOf, bridgeTypes[0].getTypeName(), bridgeTypes[1].getTypeName(),
					dataOpen && objectOpen ? "data and object" : dataOpen ? "data" : "object", converts));
		}
		Type bridgeDataType = bridgeTypes[0];
		Class<?> bridgeDataClass = bridgeTypeClass(bridgeDataType);
		Class<?> bridgeObjectClass = bridgeTypeClass(bridgeTypes[1]);

		if (!bridgeObjectClass.isAssignableFrom(info.getType())) {
			throw new CodeAnalysisException("Bridge object type not assignable from field type");
		}

		// Looked up before the bridge is built: reflection would refuse a bridge out of the public lookup's reach naming
		// this class as the one that cannot access it.
		MethodHandle toData;
		MethodHandle toObject;
		try {
			toData = MethodHandles.publicLookup().findVirtual(bridgeClass, TODATA_METHOD,
					MethodType.methodType(bridgeDataClass, bridgeObjectClass));
			toObject = MethodHandles.publicLookup().findVirtual(bridgeClass, TOOBJECT_METHOD,
					MethodType.methodType(bridgeObjectClass, bridgeDataClass));
		} catch (IllegalAccessException e) {
			throw new CodeAnalysisException(String.format("%s is bound through a member that is not accessible: "
					+ "%sis a class the public lookup cannot access", targetClass.getName(), bridgeOf), e);
		}

		@SuppressWarnings("rawtypes")
		DataBridge bridge;
		try {
			bridge = bridgeClass.getConstructor().newInstance();
		} catch (InstantiationException | IllegalArgumentException | InvocationTargetException e) {
			throw new CodeAnalysisException("Failed to instantiate bridge", e);
		}

		return new FieldBridge(bridgeDataClass, bridgeDataType,
				toData.bindTo(bridge).asType(MethodType.methodType(bridgeDataClass, info.getType())),
				toObject.bindTo(bridge).asType(MethodType.methodType(info.getType(), bridgeDataClass)));
	}

	/**
	 * {@code DataBridge}'s two type arguments as {@code type} binds them, with {@code bindings} giving its own type
	 * parameters, or {@code null} where it reaches {@code DataBridge} through a raw type: a raw type's supertypes are
	 * erased (JLS 4.8). Unlike {@code ToData}'s, the clause may be inherited. Java lets a class reach one generic
	 * interface by two paths only with the same arguments, so the first path found answers for every path.
	 */
	private static Type[] dataBridgeArguments(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
		List<Type> supertypes = new ArrayList<>(List.of(type.getGenericInterfaces()));
		if (type.getGenericSuperclass() != null) {
			supertypes.addFirst(type.getGenericSuperclass());
		}
		for (Type supertype : supertypes) {
			Class<?> raw = rawTypeOf(supertype);
			if (!DataBridge.class.isAssignableFrom(raw)) {
				continue;
			}
			if (!(supertype instanceof ParameterizedType parameterized)) {
				return raw == DataBridge.class || raw.getTypeParameters().length > 0 ? null
						: dataBridgeArguments(raw, Map.of());
			}
			Type[] arguments = parameterized.getActualTypeArguments();
			for (int x = 0; x < arguments.length; x++) {
				arguments[x] = TypeArguments.substitute(arguments[x], bindings);
			}
			if (raw == DataBridge.class) {
				return arguments;
			}
			Map<TypeVariable<?>, Type> inherited = new HashMap<>();
			TypeVariable<?>[] parameters = raw.getTypeParameters();
			for (int x = 0; x < parameters.length; x++) {
				inherited.put(parameters[x], arguments[x]);
			}
			return dataBridgeArguments(raw, inherited);
		}
		throw new IllegalStateException(type.getName() + " reaches DataBridge through none of its supertypes");
	}

	/** Whether {@code type} names a type variable, itself or as an argument at any depth. */
	private static boolean isOpen(Type type) {
		return switch (type) {
			case TypeVariable<?> _ -> true;
			case ParameterizedType parameterized ->
					Arrays.stream(parameterized.getActualTypeArguments()).anyMatch(RecordFields::isOpen);
			default -> false;
		};
	}

	private static Class<?> bridgeTypeClass(Type type) throws CodeAnalysisException {
		return switch (type) {
			case Class<?> plain -> plain;
			case ParameterizedType parameterized -> (Class<?>) parameterized.getRawType();
			default -> throw new CodeAnalysisException("Unrecognised Type");
		};
	}

	/**
	 * The class a type erases to: itself, or a parameterized type's raw type. An {@link Optional}'s own
	 * argument may be parameterized -- {@code Optional<List<X>>}, which is what an optional collection
	 * field looks like when an empty collection cannot stand for absence -- and every method handle here
	 * is typed against the erasure either way.
	 */
	static Class<?> rawTypeOf(Type type) {
		return type instanceof ParameterizedType parameterized ? (Class<?>) parameterized.getRawType() : (Class<?>) type;
	}

	/** The argument of {@code info}'s {@code Optional} type, or null where its type is no parameterized Optional. */
	static Type optionalArgument(ComponentInfo info) {
		return info.getGenericType() instanceof ParameterizedType optional && optional.getRawType() == Optional.class
				? optional.getActualTypeArguments()[0] : null;
	}

	private static DataClassField resolveOptionalField(DataBindContext context, Class<?> targetClass, ComponentInfo info,
			int index) throws DataBindException, NoSuchMethodException, IllegalAccessException {

		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);
		boolean isRequired = isRequired(info);
		String fieldName = fieldName(info);

		// An Optional's own type argument may itself be parameterized -- Optional<List<X>>, the shape an
		// optional collection field takes when an empty collection cannot stand for absence (a schema
		// field whose type carries min_items: 1). The raw type is what the method handles are typed
		// against; the full type is what the component is resolved from, so the element type survives.
		Type optionalArgument = optionalArgument(info);
		if (!(optionalArgument instanceof Class<?> || optionalArgument instanceof ParameterizedType)) {
			throw new CodeAnalysisException(String.format("%s's component '%s' is an Optional of %s, neither a class "
					+ "nor a parameterized type", targetClass.getName(), info.getName(),
					optionalArgument.getTypeName()));
		}
		Class<?> optionalType = rawTypeOf(optionalArgument);

		Supplier<DataClass> dataClass = context.componentSource(optionalType, optionalArgument);

		Lookup lookup = MethodHandles.publicLookup();

		// Optional.ofNullable(value);
		MethodHandle optionalToObject = lookup.findStatic(Optional.class, "ofNullable",
				MethodType.methodType(Optional.class, Object.class));

		// (value, elseValue ) -> value.orElse(elseValue);
		MethodHandle optionalToData = lookup.findVirtual(Optional.class, "get", MethodType.methodType(Object.class));

		// bridge.toData( targetClass.getOptional() ): optionalType
		MethodHandle optionalObject = MethodHandles.collectArguments(optionalToData, 0, accessor)
				.asType(MethodType.methodType(optionalType, targetClass));

		if (setter != null) {
			setter = MethodHandles.filterArguments(setter, 1,
					optionalToObject.asType(MethodType.methodType(Optional.class, optionalType)));
		}

		// (optional) -> optional.isPresent();
		MethodHandle isPresent = lookup.findVirtual(Optional.class, "isPresent", MethodType.methodType(boolean.class));

		isPresent = MethodHandles.collectArguments(isPresent, 0, accessor)
				.asType(MethodType.methodType(boolean.class, targetClass));

		// By definition optional is not required.
		isRequired = false;

		// TODO the constructor parameter will need to pass through the bridge.
		return new DataClassField(index, fieldName, optionalType, dataClass, isRequired, isPresent, optionalObject,
				setter);

	}

	private static DataClassField resolveOptionalNumberField(DataBindContext context, Class<?> targetClass, ComponentInfo info,
			int index) throws NoSuchMethodException, IllegalAccessException, DataBindException {

		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);

		// Optional values by definition are not required.
		boolean isRequired = false;
		String fieldName = fieldName(info);

		// This will be one of OptionalInt, OptionalLong or OptionalDouble.
		Class<?> optionalClass = info.getType();

		// work out which Optional class this is by getting return type of orElseThrow.
		Class<?> optionalType = optionalClass.getDeclaredMethod("orElseThrow").getReturnType();
		Class<?> optionalNullableType = null;
		if (optionalType == int.class) {
			optionalNullableType = Integer.class;
		} else if (optionalType == long.class) {
			optionalNullableType = Long.class;
		} else if (optionalType == double.class) {
			optionalNullableType = Double.class;
		} else {
			throw new DataBindException("Optional number field of unknown type");
		}

		// Get data class for int, long or double.
		Supplier<DataClass> dataClass = context.componentSource(optionalNullableType, optionalNullableType);

		Lookup lookup = MethodHandles.publicLookup();

		// Construct the correct getAs method name.
		String getAsMethod = "getAs" + optionalType.getSimpleName().substring(0, 1).toUpperCase()
				+ optionalType.getSimpleName().substring(1);

		// (optionalx) -> optionalx.get();
		MethodHandle optionalToData = lookup.findVirtual(optionalClass, getAsMethod,
				MethodType.methodType(optionalType));

		// bridge.toData( targetClass.getOptional() ): optionalType
		MethodHandle fieldAccessor = MethodHandles.collectArguments(optionalToData, 0, accessor)
				.asType(MethodType.methodType(optionalType, targetClass));

		// Integer.valueOf( int );
		MethodHandle wrapInteger = lookup.findStatic(optionalNullableType, "valueOf",
				MethodType.methodType(optionalNullableType, optionalType));

		fieldAccessor = MethodHandles.filterArguments(wrapInteger, 0, fieldAccessor);

		if (setter != null) {

			// (value):boolean -> Objects.nonNull(value)
			MethodHandle checkNonNull = MethodHandles.publicLookup()
					.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
					.asType(MethodType.methodType(boolean.class, optionalNullableType));

			// (int):OptionalInt -> OptionalInt.of(int);
			MethodHandle optionalIntOf = MethodHandles.publicLookup().findStatic(optionalClass, "of",
					MethodType.methodType(optionalClass, optionalType));

			// (Integer):int -> Integer.intValue()
			MethodHandle integerIntValue = MethodHandles.publicLookup().findVirtual(optionalNullableType,
					optionalType.getSimpleName() + "Value", MethodType.methodType(optionalType));

			// (Integer):OptionalInt -> OptionalInt.of( Integer.intValue(i) )
			optionalIntOf = MethodHandles.collectArguments(optionalIntOf, 0, integerIntValue);

			// ():OptionalInt -> OptionalInt.empty()
			MethodHandle optionalIntEmpty = MethodHandles.publicLookup().findStatic(optionalClass, "empty",
					MethodType.methodType(optionalClass));

			// (Object[]):OptionalInt -> OptionalInt.empty()
			optionalIntEmpty = MethodHandles.dropArguments(optionalIntEmpty, 0, optionalNullableType);

			// (Integer):OptionalInt -> if (Object.nonNull(Integer)) { return OptionalInt.of(
			// Integer.intValue(i) ) } else { return OptionalInt.empty() }
			MethodHandle toOptional = MethodHandles.guardWithTest(checkNonNull, optionalIntOf, optionalIntEmpty);

			// (obj, Integer):void -> obj.setOptionalInt( toOptional(Integer) )
			setter = MethodHandles.filterArguments(setter, 1, toOptional);
		}

		// (optional) -> optional.isPresent();
		MethodHandle isPresent = lookup.findVirtual(optionalClass, "isPresent", MethodType.methodType(boolean.class));

		isPresent = MethodHandles.collectArguments(isPresent, 0, accessor)
				.asType(MethodType.methodType(boolean.class, targetClass));

		return new DataClassField(index, fieldName, optionalNullableType, dataClass, isRequired, isPresent,
				fieldAccessor, setter);
	}
}
