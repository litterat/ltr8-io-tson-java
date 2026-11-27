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

import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.analysis.RecordFields.FieldBridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The handle that builds a record class from the values array a reader fills: its constructor given the components
 * it takes, then the setter of each other bound component.
 */
final class RecordCreators {

	private RecordCreators() {
	}

	/**
	 * {@code (Object[], int) -> Object}, where a component's value comes from: the values array, or for a component
	 * the class owns ({@code @Unbound}) a stand-in of the same shape returning its type's zero, so the rest of the
	 * pipeline -- bridges, {@code Optional} wrapping, primitive unboxing -- applies unchanged. The zero rather than
	 * {@code null}, which no primitive can take.
	 */
	private static MethodHandle valueSource(ComponentInfo field, FieldBridge bridge, int slot) {
		if (slot >= 0) {
			return MethodHandles.arrayElementGetter(Object[].class);
		}
		// A bridge converts from its data class, which is never primitive.
		Class<?> type = bridge != null ? bridge.dataClass() : field.getType();
		return MethodHandles.dropArguments(MethodHandles.zero(type).asType(MethodType.methodType(Object.class)), 0,
				Object[].class, int.class);
	}

	/**
	 * Creates a single MethodHandle that both constructs an object and sets any setters using a single
	 * Object[] as input. Fields are passed in through the real constructor or through the setters if
	 * present.
	 */
	static MethodHandle createTupleConstructor(Class<?> dataClass, List<ComponentInfo> fields,
			DataClassField[] dataFields, FieldBridge[] bridges, int[] slots, MethodHandle dataConstructor)
			throws IllegalAccessException, NoSuchMethodException, CodeAnalysisException {

		// (Object[]):serialClass -> ctor(Object[])
		MethodHandle create = createEmbedConstructor(dataClass, dataConstructor, fields, dataFields, bridges, slots);

		// (serialClass, Object[]) -> serialClass.setValues(Object[x]);
		MethodHandle setters = createEmbedSetters(dataClass, fields, dataFields, bridges, slots);

		// (Object[], Object[]) -> ctor(Object[]).setvalues(Object[])
		MethodHandle createAndSet = MethodHandles.collectArguments(setters, 0, create);

		// (Object[]):dataObject
		int[] permuteInput = new int[2];

        return MethodHandles.permuteArguments(createAndSet,
                MethodType.methodType(dataClass, Object[].class), permuteInput);
	}

	/**
	 * Builds a constructor that takes Object[] as constructor arguments and return an object instance.
	 * Passes relevant fields into the data class constructor.
	 */
	private static MethodHandle createEmbedConstructor(Class<?> dataClass, MethodHandle dataConstructor,
			List<ComponentInfo> fields, DataClassField[] dataFields, FieldBridge[] bridges, int[] slots)
			throws NoSuchMethodException, IllegalAccessException, CodeAnalysisException {
		MethodHandle result = dataConstructor;

		for (int x = 0; x < fields.size(); x++) {
			ComponentInfo field = fields.get(x);

			if (field.getConstructorArgument() >= 0) {

				int arg = field.getConstructorArgument();

				// An Optional component the class owns still arrives empty rather than null.
				MethodHandle arrayGetter = valueSource(field, bridges[x], slots[x]);

				// () -> inputIndex
				MethodHandle index = MethodHandles.constant(int.class, Math.max(slots[x], 0));

				// (values[]) -> values[inputIndex] and maybe additional modifications to the value.
				MethodHandle arrayIndexGetter = dataArrayToObject(field, dataFields[x], bridges[x], arrayGetter,
						index);

				// ()-> constructor( ..., values[inputIndex] , ... )
				result = MethodHandles.collectArguments(result, arg, arrayIndexGetter);
			}
		}

		// spread the arguments so ctor(Object[],Object[]...) becomes ctor(Object[])
		int paramCount = dataConstructor.type().parameterCount();
		if (paramCount > 0) {
			int[] permuteInput = new int[paramCount];
			result = MethodHandles.permuteArguments(result, MethodType.methodType(dataClass, Object[].class),
					permuteInput);
		} else {

			result = MethodHandles.dropArguments(result, 0, Object[].class);
		}
		return result;
	}

	/**
	 * Creates a MethodHandle that takes an Object[] as input and calls any field setters for mutable objects.
	 */
	private static MethodHandle createEmbedSetters(Class<?> dataClass, List<ComponentInfo> fields,
			DataClassField[] dataFields, FieldBridge[] bridges, int[] slots)
			throws IllegalAccessException, NoSuchMethodException, SecurityException, CodeAnalysisException {

		// (dataClass,Object[]):dataClass -> return object;
		MethodHandle identity = MethodHandles.identity(dataClass);

		// (dataClass, Object[]):dataClass -> return dataClass;
		MethodHandle result = MethodHandles.dropArguments(identity, 1, Object[].class);

		for (int x = 0; x < fields.size(); x++) {

			ComponentInfo field = fields.get(x);

            // An unbound component keeps what the class gave it rather than a zero no document supplied.
            if (field.getWriteMethod() != null && field.getConstructorArgument() < 0 && !field.isUnbound()) {

				// (obj, value):void -> obj.setField( value );
				MethodHandle fieldSetter = field.getWriteMethod();

				MethodHandle arrayGetter = valueSource(field, bridges[x], slots[x]);

				// ():int -> inputIndex
				MethodHandle index = MethodHandles.constant(int.class, Math.max(slots[x], 0));

				// (values[]) -> values[inputIndex] and maybe additional modifications to the value.
				MethodHandle arrayIndexGetter = dataArrayToObject(field, dataFields[x], bridges[x], arrayGetter,
						index);

				// (obj, value[]):void -> obj.setField( value[inputIndex] );
				MethodHandle arrayFieldSetter = MethodHandles.collectArguments(fieldSetter, 1, arrayIndexGetter);

				// add to list of setters.
				result = MethodHandles.foldArguments(result, arrayFieldSetter);
			}
		}

		return result;
	}

	/**
	 * The constructor uses an Object[] as the carrier to either call a constructor and/or call the
	 * required setters of an object. Some field types such as bridges, Optional, OptionalInt requires
	 * special handling to prepare the value prior to calling the constructor or setter. This makes the
	 * required adaptations.
	 *
	 * @param field The field information that needs to be adapted. Includes reflection information
	 * @param dataField The DataClassField that has the data field information.
	 * @param bridge The field's {@code @Field} bridge, or {@code null} where it names none.
	 * @param arrayGetter MethodHandle with (value[],x):Object -> value[x]
	 * @param index index of the field
	 * @return MethodHandle with (values[]) -> values[inputIndex]) and can perform additional modifications.
	 */
	private static MethodHandle dataArrayToObject(ComponentInfo field, DataClassField dataField, FieldBridge bridge,
			MethodHandle arrayGetter, MethodHandle index)
			throws CodeAnalysisException, IllegalAccessException, NoSuchMethodException, SecurityException {

		MethodHandle arrayIndexGetter;

		if (bridge != null) {

			// (values[]) -> values[inputIndex]:bridgeDataClass
			arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(bridge.dataClass(), Object[].class));

			// (values[]):fieldType -> bridge.toObject( (bridgeData) values[inputIndex] )
			arrayIndexGetter = MethodHandles.collectArguments(bridge.toObject(), 0, arrayIndexGetter);

		} else if (RecordFields.optionalArgument(field) != null) {

			// The method handles are typed against the raw class, so an Optional whose own argument is
			// parameterized -- Optional<List<X>> -- erases to the collection interface here. Only the
			// component resolution needs the full type, and that happened in resolveOptionalField.
			Class<?> optionalType = RecordFields.rawTypeOf(RecordFields.optionalArgument(field));

			// (values[]) -> values[inputIndex]:optionalType
			arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(optionalType, Object[].class));

			// (value) -> Optional.ofNullable(value);
			MethodHandle optionalToObject = MethodHandles.publicLookup()
					.findStatic(Optional.class, "ofNullable", MethodType.methodType(Optional.class, Object.class))
					.asType(MethodType.methodType(Optional.class, optionalType));

			// (values[]):Optional<fieldType> -> bridge.toObject( values[inputIndex] ):Optional
			arrayIndexGetter = MethodHandles.collectArguments(optionalToObject, 0, arrayIndexGetter);
			// .asType(MethodType.methodType(field.getType(), Object[].class));
		} else if (field.getType() == OptionalInt.class
				|| field.getType() == OptionalLong.class | field.getType() == OptionalDouble.class) {

			// This is one of those ugly edge cases that is not supported by MethodHandles. OptionalInt type
			// optional primitives need to be unboxed from Integer for the constructor.
			// This requires a MethodHandle that looks as follows:
			// if (Object.nonNull(Integer)) { return OptionalInt.of( Integer.intValue(i) ) } else { return
			// OptionalInt.empty() }

			// This will be one of OptionalInt, OptionalLong or OptionalDouble.
			Class<?> optionalClass = field.getType();

			// work out which Optional class this is by getting return type of orElseThrow.
			Class<?> optionalType = optionalClass.getDeclaredMethod("orElseThrow").getReturnType();

			Class<?> optionalNullableType = null;
			if (optionalType == int.class) {
				optionalNullableType = Integer.class;
			} else if (optionalType == long.class) {
				optionalNullableType = Long.class;
			} else if (optionalType == double.class) {
				optionalNullableType = Double.class;
			}

			// Get data class for int, long or double.
			// DataClass optionalDataClass = context.getDescriptor(optionalType);

			// (values[]):optionalType -> values[inputIndex]:optionalType
			MethodHandle arrayGet = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(optionalNullableType, Object[].class));

			// (value):boolean -> Objects.nonNull(value)
			MethodHandle checkNonNull = MethodHandles.publicLookup()
					.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
					.asType(MethodType.methodType(boolean.class, optionalNullableType));

			// (values[]):boolean -> Objects.nonNull(values[index])
			checkNonNull = MethodHandles.collectArguments(checkNonNull, 0, arrayGet);

			// (int):OptionalInt -> OptionalInt.of(int);
			MethodHandle optionalIntOf = MethodHandles.publicLookup().findStatic(optionalClass, "of",
					MethodType.methodType(optionalClass, optionalType));

			// (Integer):int -> Integer.intValue()
			MethodHandle integerIntValue = MethodHandles.publicLookup().findVirtual(optionalNullableType,
					optionalType.getSimpleName() + "Value", MethodType.methodType(optionalType));

			// (Integer):OptionalInt -> OptionalInt.of( Integer.intValue(i) )
			optionalIntOf = MethodHandles.collectArguments(optionalIntOf, 0, integerIntValue);

			// (values[]):OptionalInt -> OptionalInt.of( Integer.intValue(values[inputIndex]) )
			optionalIntOf = MethodHandles.collectArguments(optionalIntOf, 0, arrayGet);

			// ():OptionalInt -> OptionalInt.empty()
			MethodHandle optionalIntEmpty = MethodHandles.publicLookup().findStatic(optionalClass, "empty",
					MethodType.methodType(optionalClass));

			// (Object[]):OptionalInt -> OptionalInt.empty()
			optionalIntEmpty = MethodHandles.dropArguments(optionalIntEmpty, 0, Object[].class);

			// (values[]):optionalType -> if (Object.nonNull(Integer)) { return OptionalInt.of(
			// Integer.intValue(i) ) } else { return OptionalInt.empty() }
			arrayIndexGetter = MethodHandles.guardWithTest(checkNonNull, optionalIntOf, optionalIntEmpty);

		} else if (field.getUnion() != null) {
			// (values[]):fieldType -> values[inputIndex]
			arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(field.getType(), Object[].class));

			DataClassUnion dataUnion = (DataClassUnion) dataField.dataClass();

			// (values[]):fieldType -> dataUnion.checkIsMember( values[inputIndex] )
			MethodHandle unionCheck = MethodHandles.publicLookup().findVirtual(dataUnion.getClass(), "checkIsMember",
					MethodType.methodType(Object.class, Object.class)).bindTo(dataUnion);

			arrayIndexGetter = MethodHandles.filterReturnValue(arrayIndexGetter, unionCheck);

		} else {
			// (values[]):fieldType -> values[inputIndex]
			arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(field.getType(), Object[].class));
		}

		return arrayIndexGetter;

	}
}
