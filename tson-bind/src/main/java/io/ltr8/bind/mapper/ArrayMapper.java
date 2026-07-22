/*
 * Copyright (c) 2020-2021, Litterat Pty Ltd. All Rights Reserved.
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
package io.ltr8.bind.mapper;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sample showing how to use the Litterat bind library to convert an Object to/from Object[]
 * <p>
 * This is intentionally using MethodHandles throughout to demonstrate pre-building method handles
 * for each type. This is the method likely to be used by serialization libraries to improve
 * performance.
 * 
 * <ul>
 * <li>TODO add try/catch/throw around conversions
 * </ul>
 *
 * 
 */
public class ArrayMapper {

	private final DataBindContext context;

	private final Map<DataClass, ArrayFunctions> functionCache;

	private static class ArrayFunctions {

		// Converts from Object[] to targetClass. Has signature: Object[] project( T object );
		public final MethodHandle toArray;

		// constructs, calls setters and embeds. Has signature: T embed( Object[] values ).
		public final MethodHandle toObject;

		public ArrayFunctions(MethodHandle toArray, MethodHandle toObject) {
			this.toArray = toArray;
			this.toObject = toObject;
		}
	}

	public ArrayMapper(DataBindContext context) {
		this.context = context;
		this.functionCache = new HashMap<>();

	}

	private ArrayFunctions getFunctions(DataClass dataClass) throws DataBindException {
		ArrayFunctions af = functionCache.get(dataClass);
		if (af == null) {

			MethodHandle toArray = createToDataFunction(dataClass);
			MethodHandle toObject = createToObjectFunction(dataClass);

			af = new ArrayFunctions(toArray, toObject);
			functionCache.put(dataClass, af);
		}
		return af;
	}

	/**
	 * Convenience function. Takes the target object of this descriptor and return an object array.
	 *
	 * @param o target object instance to project to object[]
	 * @return values from target object
	 * @throws Throwable any failure from the project function.
	 */
	public Object[] toArray(Object o) throws Throwable {
		return toArray(context.getDescriptor(o.getClass()), o);
	}

	public Object[] toArray(DataClass clss, Object o) throws Throwable {
		Objects.requireNonNull(o);

		ArrayFunctions af = getFunctions(clss);

		// create and fill array with project instance.
		return (Object[]) af.toArray.invoke(o);
	}

	/**
	 * Convenience function. Takes an array of values based on the field types and returns the target
	 * object.
	 *
	 * @param values object values to embed into target object.
	 * @return recreated target object.
	 * @throws Throwable any failure from the embed function.
	 */
	public <T> T toObject(Class<T> clss, Object[] values) throws Throwable {
		return toObject(context.getDescriptor(clss), values);
	}

	public <T> T toObject(DataClass clss, Object[] values) throws Throwable {
		Objects.requireNonNull(clss);
		Objects.requireNonNull(values);

		ArrayFunctions af = getFunctions(clss);

		// create the embedded object.
		return (T) af.toObject.invoke(values);
	}

	/**
	 * Creates the embed method handle. Will create the serial instance, call setters, and class the
	 * embed method handle to create the target object in a single call. This is equivalent to:
	 * <p>
	 * // fields mapped as required from value array. T t = new EmbedClass( values[0], values[1], ... );
	 * <p>
	 * // calls the embed function on the object. return toObject( t );
	 */
	private MethodHandle createToObjectFunction(DataClass dataClass) throws DataBindException {

		// return MethodHandles.collectArguments(dataClass.toObject(), 0, create);
		MethodHandle result = null;

        switch (dataClass) {
            case DataClassAtom dataClassAtom -> {
				// Use identity here because calling function wraps the toObject method.
				// identity -> ( dataObject ):dataObject
				if (dataClassAtom.bridge().isPresent()) {
					result = MethodHandles.identity(dataClassAtom.bridge().get().dataClass());
				} else {
					result = MethodHandles.identity(dataClass.typeClass());
				}
				// ( Object ):dataObject
				//result = result.asType(result.type().changeParameterType(0, Object.class));
			}
            case DataClassRecord dataClassRecord -> {

				// (Object[]):typeClass -> new TypeClass( params );
                result = dataClassRecord.constructor();

                DataClassField[] fields = dataClassRecord.fields();
                for (int fieldIndex = 0; fieldIndex < dataClassRecord.fields().length; fieldIndex++) {
					DataClass fieldDataClass = fields[fieldIndex].dataClass();

                    // (Object[],int) -> values[int]:Object
                    MethodHandle arrayGetter = MethodHandles.arrayElementGetter(Object[].class);

                    // () -> fieldIndex
                    MethodHandle index = MethodHandles.constant(int.class, fieldIndex);

                    // (Object[]):Object -> (values[inputIndex]):Object
                    MethodHandle arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index);

					// (dataClass):typeClass -> toObject(dataClass):typeClass
					MethodHandle fieldToObject = createToObjectFunction(fieldDataClass);

					// (values[]) -> (values[inputIndex)):dataType
					MethodType arrayIndexGetterType = arrayIndexGetter.type().changeReturnType(fieldToObject.type().parameterType(0));
					MethodHandle typedArrayIndexGetter = arrayIndexGetter.asType(arrayIndexGetterType);

                    // (values[]):fieldType -> toObject(values[x])
                    arrayIndexGetter = MethodHandles.collectArguments(fieldToObject, 0, typedArrayIndexGetter);

                    // (values[],int,Object):void -> values[int] = Object;
                    MethodHandle arraySetter = MethodHandles.arrayElementSetter(Object[].class);

                    // (values[],Object):void -> values[x] = Object;
                    MethodHandle arrayIndexSetter = MethodHandles.collectArguments(arraySetter, 1, index);

                    // (values[],Object[]):void -> values[x] = toObject(values[x]);
                    MethodHandle arrayValueSetter = MethodHandles.collectArguments(arrayIndexSetter, 1,
                            arrayIndexGetter.asType(MethodType.methodType(Object.class, Object[].class)));

                    int[] permuteInput = new int[2];
                    MethodHandle combined = MethodHandles.permuteArguments(arrayValueSetter,
                            MethodType.methodType(void.class, Object[].class), permuteInput);

                    result = MethodHandles.foldArguments(result, combined);

                }

                result = result.asType(result.type().changeReturnType(dataClass.dataClass()));
            }
            case DataClassArray dataArrayClass -> {

                // toObject( Object[] ):<array>
                try {

                    MethodHandle valueToData = createToObjectFunction(dataArrayClass.arrayDataClass());

                    ArrayToObjectBridge bridge = new ArrayToObjectBridge(dataArrayClass, valueToData);

                    result = MethodHandles.lookup().findVirtual(ArrayToObjectBridge.class, "toObject",
                            MethodType.methodType(Object.class, Object[].class)).bindTo(bridge);

                    result = result.asType(MethodType.methodType(dataArrayClass.typeClass(), Object.class));

                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new DataBindException("failed to build bridge for array", e);
                }
            }
            case DataClassUnion dataUnionClass -> {

                // toObject( Object[] ):<array>
                try {

                    UnionToObjectBridge bridge = new UnionToObjectBridge(dataUnionClass);

                    result = MethodHandles.lookup().findVirtual(UnionToObjectBridge.class, "toObject",
                            MethodType.methodType(Object.class, Object[].class)).bindTo(bridge);

                    result = result.asType(MethodType.methodType(dataUnionClass.typeClass(), Object.class));

                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new DataBindException("failed to build bridge for array", e);
                }
            }
            case null, default -> throw new DataBindException("unexpected data class type");
        }

		if (dataClass.bridge().isPresent()) {
			DataClassBridge bridge = dataClass.bridge().get();

			// toObject( dataObject ):targetObject
			MethodHandle toObject = bridge.toObject();

			// (Object[]) -> toObject( result )
			result = MethodHandles.collectArguments(toObject, 0, result);
		}

		return result;
	}

	/**
	 * create project takes a target object and returns an Object[] of values. Not yet complete. Haven't
	 * worked out how to re-use the Object[] in return value.
	 * <p>
	 * // Project the instance to the embedded version. EmbeddedClass e = project.invoke(o)
	 * <p>
	 * // Extract the values from the projected object. Object[] values = new Object[fields.length];
	 * <p>
	 * // Call the various accessors to fill in the array and return values. return getter.invoke(e,
	 * values );
	 */
	private MethodHandle createToDataFunction(DataClass dataClass) throws DataBindException {

		MethodHandle toDataMethod = null;

        switch (dataClass) {
			case DataClassAtom dataClassAtom -> {
				// (typeClass):typeClass
				toDataMethod = MethodHandles.identity(dataClass.dataClass());
			}
            case DataClassRecord dataClassRecord -> {

                // (int):Object[] -> new Object[int]
                MethodHandle createArray = MethodHandles.arrayConstructor(Object[].class);

                // (int):length -> fields.length
                MethodHandle index = MethodHandles.constant(int.class, dataClassRecord.fields().length);

                // ():Object[] -> new Object[fields.length]
                MethodHandle arrayCreate = MethodHandles.collectArguments(createArray, 0, index);

                // (Object[],serialClass):void -> getters(Object[],serialClass)
                MethodHandle getters = createProjectGetters(dataClassRecord);

                // (Object[],targetClass):void -> getters(Object[], project(targetClass))
                //MethodHandle projectGetters = MethodHandles.collectArguments(getters, 1, dataClassRecord.toData());

                // (Object):Object[] -> return getters(new Object[fields.length], serialiClass);
                toDataMethod = MethodHandles.collectArguments(getters, 0, arrayCreate);
            }
            case DataClassArray dataClassArray -> {

                try {

                    DataClassArray arrayClass = (DataClassArray) dataClass;

                    MethodHandle arrayToData = createToDataFunction(arrayClass.arrayDataClass());

                    ObjectToArrayBridge bridge = new ObjectToArrayBridge(arrayClass, arrayToData);

                    MethodHandle bridgeToData = MethodHandles.lookup().findVirtual(ObjectToArrayBridge.class, "toData",
                            MethodType.methodType(Object[].class, Object.class)).bindTo(bridge);

                    toDataMethod = bridgeToData.asType(MethodType.methodType(Object.class, dataClass.typeClass()));
                    // fieldBox = MethodHandles.collectArguments(bridgeToData, 0, fieldBox);
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new DataBindException("failed to build array bridge", e);
                }
            }
            case DataClassUnion dataClassUnion -> {
				try {

					DataClassUnion unionClass = (DataClassUnion) dataClass;

					ObjectToUnionBridge bridge = new ObjectToUnionBridge(unionClass);

					MethodHandle bridgeToData = MethodHandles.lookup().findVirtual(ObjectToUnionBridge.class, "toData",
							MethodType.methodType(Object[].class, Object.class)).bindTo(bridge);

					toDataMethod = bridgeToData.asType(MethodType.methodType(Object.class, dataClass.typeClass()));

				} catch (NoSuchMethodException | IllegalAccessException e) {
					throw new DataBindException("failed to build array bridge", e);
				}
			}

            case null, default -> throw new DataBindException("unexpected data class type");
        }

		if (dataClass.bridge().isPresent()) {
			DataClassBridge bridge = dataClass.bridge().get();

			// toData( targetObject ):dataObject
			MethodHandle toData = bridge.toData();

			// (typeClass):Object[] -> toData( result ):Object[]
			toDataMethod = MethodHandles.collectArguments(toDataMethod, 0, toData);
		}

		return toDataMethod;
	}

	/**
	 * Creates a MethodHandle which converts a Record value into an Object[]. It accepts an empty
	 * object[] and the record object, and for each field check if a value is present and if available,
	 * calls the accessor and sets the field.
	 */
	private MethodHandle createProjectGetters(DataClassRecord dataClass) throws DataBindException {

		// (object[]):object[] -> return object[];
		MethodHandle identity = MethodHandles.identity(Object[].class);

		// (Object[], dataClass):object[] -> return object[];
		MethodHandle result = MethodHandles.dropArguments(identity, 1, dataClass.dataClass());

		DataClassField[] fields = dataClass.fields();
		for (int x = 0; x < fields.length; x++) {

			DataClassField field = fields[x];
			int outputIndex = x;

			// (value[],x, v) -> value[x] = v
			MethodHandle arraySetter = MethodHandles.arrayElementSetter(Object[].class);

			// () -> outputIndex
			MethodHandle index = MethodHandles.constant(int.class, outputIndex);

			// (value[],v) -> value[inputIndex] = v
			MethodHandle arrayIndexSetter = MethodHandles.collectArguments(arraySetter, 1, index);

			DataClass fieldDataClass = field.dataClass();

			// (<targettype>):<fieldtype> -> (Object) object.getter()
			MethodHandle fieldBox = field.accessor();

			if (field.dataClass().typeClass() == dataClass.typeClass()) {
				throw new IllegalArgumentException("Recursive structures not yet supported for array mapper");
			}

			// ( typeClass ):dataClass -> (dataClass) toData(typeClass)
			MethodHandle toDataFunction = createToDataFunction(fieldDataClass);

			// (<targettype>):<fieldtype> -> toData( object.getter() );
			fieldBox = MethodHandles.collectArguments(toDataFunction, 0, fieldBox)
					.asType(MethodType.methodType(Object.class, dataClass.dataClass()));

			// (value[],object):void -> value[inputIndex] = toData( object.getter() )
			MethodHandle arrayValueSetter = MethodHandles.collectArguments(arrayIndexSetter, 1, fieldBox);

			// (object):boolean -> object.isPresent(object);
			MethodHandle isPresent = field.isPresent();

			// (value[],object):boolean -> object.isPresent(object);
			MethodHandle isPresentTest = MethodHandles.dropArguments(isPresent, 0, Object[].class);

			// ():void -> void;
			MethodHandle noop = MethodHandles.constant(Void.class, null).asType(MethodType.methodType(void.class));

			// (value[],object):void -> void;
			MethodHandle noopElse = MethodHandles.dropArguments(noop, 0, Object[].class, dataClass.dataClass());

			// (value[],object):void -> if(isPresent(object)) { value[inputIndex] = toData( object.getter() ) }
			MethodHandle checkAndSet = MethodHandles.guardWithTest(isPresentTest, arrayValueSetter, noopElse);

			// add to list of getters.
			result = MethodHandles.foldArguments(result, checkAndSet);

		}

		// (object[]):object[] -> ... callGetters(object[]); ... return object[];
		return result;
	}

	// The ArrayBridge is an easy way out of not writing loops using MethodHandles.
	// It can be done but is more difficult than above.
	private static class ObjectToArrayBridge {

		private final DataClassArray arrayClass;
		private final MethodHandle dataToObject;

		public ObjectToArrayBridge(DataClassArray fieldDataClass, MethodHandle dataToObject) {
			this.arrayClass = fieldDataClass;

			this.dataToObject = MethodHandles.collectArguments(
					dataToObject
							.asType(dataToObject.type().changeParameterType(0, arrayClass.get().type().returnType())),
					0, arrayClass.get());
		}

		@SuppressWarnings("unused")
		public Object[] toData(Object v) throws DataBindException {
			try {

				Object arrayData = v;
				int length = (int) arrayClass.size().invoke(arrayData);
				Object[] outputArray = new Object[length];
				Object iterator = arrayClass.iterator().invoke(arrayData);

				DataClass arrayDataClass = arrayClass.arrayDataClass();

				for (int x = 0; x < length; x++) {
					outputArray[x] = dataToObject.invoke(arrayData, iterator);
				}

				return outputArray;

			} catch (Throwable e) {
				throw new DataBindException("Failed to convert array", e);
			}
		}

	}

	private class ObjectToUnionBridge {

		private final DataClassUnion unionClass;
		private final Map<Class<?>, ArrayFunctions> typeMap;

		public ObjectToUnionBridge(DataClassUnion unionClass) {
			this.unionClass = unionClass;
			this.typeMap = new HashMap<>();
		}

		@SuppressWarnings("unused")
		public Object[] toData(Object v) throws DataBindException {

			Object[] unionPair = new Object[2];
			if (v != null) {
				unionPair[0] = v.getClass().getName();

				ArrayFunctions function = typeMap.get(v.getClass());
				if (function == null) {

					DataClass dataClass = context.getDescriptor(v.getClass());

					if (!unionClass.isMemberType(dataClass.typeClass())) {
						throw new DataBindException("Class not of union type");
					}

					function = getFunctions(dataClass);
					if (function != null) {
						typeMap.put(v.getClass(), function);
					} else {
						throw new DataBindException("no descriptor for class");
					}
				}

				try {
					unionPair[1] = function.toArray.invoke(v);
				} catch (Throwable e) {
					throw new DataBindException("Failed to convert class", e);
				}
			} else {
				unionPair[0] = "null";
				unionPair[1] = null;
			}
			return unionPair;
		}
	}

	private static class ArrayToObjectBridge {

		private final DataClassArray arrayClass;
		private final MethodHandle arrayToObject;

		public ArrayToObjectBridge(DataClassArray arrayClass, MethodHandle arrayToObject) {
			this.arrayClass = arrayClass;
			this.arrayToObject = arrayToObject;

			// TODO revisit this. Difficult to align types correctly. Might need a explicit
			// cast.
			// this.arrayToObject = MethodHandles.collectArguments(arrayClass.put(), 2,
			// arrayToObject.asType(MethodType.methodType(Object.class,
			// arrayToObject.type().parameterType(0))));
		}

		@SuppressWarnings("unused")
		public Object toObject(Object[] s) throws DataBindException {
			try {
				Object[] inputArray = s;

				int length = inputArray.length;
				Object arrayData = arrayClass.constructor().invoke(length);
				Object iterator = arrayClass.iterator().invoke(arrayData);

				DataClass arrayDataClass = arrayClass.arrayDataClass();

				for (int x = 0; x < length; x++) {

					Object v = arrayToObject.invoke(inputArray[x]);

					arrayClass.put().invoke(arrayData, iterator, v);

				}

				return arrayData;
			} catch (Throwable e) {
				throw new DataBindException("Failed to convert array", e);
			}
		}
	}

	private class UnionToObjectBridge {
		private final DataClassUnion unionClass;
		private final Map<String, ArrayFunctions> typeMap;

		public UnionToObjectBridge(DataClassUnion unionClass) {
			this.unionClass = unionClass;
			this.typeMap = new HashMap<>();
		}

		@SuppressWarnings("unused")
		public Object toObject(Object[] s) throws DataBindException {
			Object[] unionPair = s;
			if (unionPair == null) {
				return null;
			}

			String unionType = (String) unionPair[0];
			Object unionValue = unionPair[1];
			Object value = null;

			if (!unionType.equals("null")) {

				try {
					ArrayFunctions function = typeMap.get(unionType);
					if (function == null) {

						DataClass dataClass = context.getDescriptor(Class.forName(unionType));
						if (!unionClass.isMemberType(dataClass.typeClass())) {
							throw new DataBindException("Class not of union type");
						}

						function = getFunctions(dataClass);
						if (function != null) {
							typeMap.put(unionType, function);
						} else {
							throw new DataBindException("no descriptor for class");
						}
					}

					value = function.toObject.invoke(unionValue);
				} catch (Throwable e) {
					throw new DataBindException("Failed to convert class", e);
				}
			}

			return value;
		}

	}
}
