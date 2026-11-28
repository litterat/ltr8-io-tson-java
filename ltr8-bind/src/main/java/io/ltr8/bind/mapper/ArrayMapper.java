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

import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sample showing how to use the Litterat bind library to convert an Object to/from Object[]
 * <p>
 * This is intentionally using MethodHandles throughout to demonstrate pre-building method handles
 * for each type. This is the method likely to be used by serialization libraries to improve
 * performance.
 * <p>
 * A record and a {@code @Tuple} record are each an array of their values, one per field or element in order, each
 * in this form. A map is an array of {@code [key, value]} entries, each part in this form, in the order the map
 * iterates in. An annotated value, an {@code AnnotatedMap}'s key among them, is its value in this form: the form has
 * no place for annotations, so it reads back without them. For the same reason a record's {@code Annotations}
 * carrier takes no place in its array, and reads back holding none.
 * <p>
 * A union value is a {@code [name, value]} pair of its class's name and its value in this form. A read refuses a name
 * of a class that cannot be a member before that class is initialised or described: a class the union lists can be,
 * and so can a class of the union's type where the union admits additions.
 * <p>
 * A read refused for a record's or a tuple's own shape -- its array's length, or the absence of a required field or
 * a primitive element -- and a write of a record holding no value for a required field, throw the
 * {@link DataBindException} that says so, however deep the value sits: no enclosing list, map or union wraps it.
 *
 * <ul>
 * <li>TODO add try/catch/throw around conversions
 * </ul>
 *
 * 
 */
public class ArrayMapper {

	private static final MethodHandle CHECKED;

	private static final MethodHandle PART;

	private static final MethodHandle ABSENT;

	private static final MethodHandle DEFERRED_TO_DATA;

	private static final MethodHandle DEFERRED_TO_OBJECT;

	static {
		try {
			MethodType convert = MethodType.methodType(Object.class, Object.class);
			DEFERRED_TO_DATA = MethodHandles.lookup().findVirtual(Deferred.class, "toData", convert);
			DEFERRED_TO_OBJECT = MethodHandles.lookup().findVirtual(Deferred.class, "toObject", convert);
			CHECKED = MethodHandles.lookup().findStatic(ArrayMapper.class, "checked",
					MethodType.methodType(Object[].class, DataClassRecord.class, Object[].class));
			ABSENT = MethodHandles.lookup().findStatic(ArrayMapper.class, "absent",
					MethodType.methodType(void.class, DataClassRecord.class, DataClassField.class));
			PART = MethodHandles.lookup().findStatic(ArrayMapper.class, "part",
					MethodType.methodType(Object.class, MethodHandle.class, Object.class));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

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
		this.functionCache = new ConcurrentHashMap<>();

	}

	/**
	 * A mapper may be shared by threads, so the cache is concurrent and a lost race keeps the winner's functions, as
	 * {@link DataBindContext#getDescriptor(Class, java.lang.reflect.Type)} does. Built outside the map rather than in
	 * {@code computeIfAbsent}, which would hold a lock for a walk of the whole type graph.
	 */
	private ArrayFunctions getFunctions(DataClass dataClass) throws DataBindException {
		ArrayFunctions af = functionCache.get(dataClass);
		if (af == null) {

			MethodHandle toArray = toDataFunction(dataClass, new HashSet<>());
			MethodHandle toObject = toObjectFunction(dataClass, new HashSet<>());

			ArrayFunctions built = new ArrayFunctions(toArray, toObject);
			af = functionCache.putIfAbsent(dataClass, built);
			if (af == null) {
				af = built;
			}
		}
		return af;
	}

	/**
	 * {@code building} holds the classes this walk is building functions for, from its root down: a class reached
	 * again among them closes a cycle, so the edge back is {@link Deferred} instead of being built without end.
	 */
	private MethodHandle toDataFunction(DataClass dataClass, Set<DataClass> building) throws DataBindException {
		if (!building.add(dataClass)) {
			return DEFERRED_TO_DATA.bindTo(new Deferred(dataClass))
					.asType(MethodType.methodType(Object.class, dataClass.typeClass()));
		}
		try {
			return createToDataFunction(dataClass, building);
		} finally {
			building.remove(dataClass);
		}
	}

	private MethodHandle toObjectFunction(DataClass dataClass, Set<DataClass> building) throws DataBindException {
		if (!building.add(dataClass)) {
			return DEFERRED_TO_OBJECT.bindTo(new Deferred(dataClass))
					.asType(MethodType.methodType(dataClass.typeClass(), Object.class));
		}
		try {
			return createToObjectFunction(dataClass, building);
		} finally {
			building.remove(dataClass);
		}
	}

	/**
	 * The edge back to a class whose functions are still being built. It looks them up when a value reaches it, by
	 * when they exist -- or builds them then, where the walk that met the cycle was building another class's.
	 */
	private final class Deferred {

		private final DataClass dataClass;

		Deferred(DataClass dataClass) {
			this.dataClass = dataClass;
		}

		@SuppressWarnings("unused")
		public Object toData(Object value) throws Throwable {
			return getFunctions(dataClass).toArray.invoke(value);
		}

		@SuppressWarnings("unused")
		public Object toObject(Object value) throws Throwable {
			return getFunctions(dataClass).toObject.invoke(value);
		}
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

	/** @throws DataBindException where {@code clss} is written as one value, which is no array */
	public Object[] toArray(DataClass clss, Object o) throws Throwable {
		Objects.requireNonNull(o);

		ArrayFunctions af = getFunctions(clss);

		if (!(af.toArray.invoke(o) instanceof Object[] written)) {
			throw new DataBindException(String.format("%s is not written as an array: an ArrayMapper writes an atom "
					+ "only inside a record, tuple, list, map or union", clss.typeClass().getTypeName()));
		}
		return written;
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
	private MethodHandle createToObjectFunction(DataClass dataClass, Set<DataClass> building)
			throws DataBindException {

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
            case DataClassAnnotated annotated -> {
                // (Object):box -> box(part(toObject, value), no annotations)
                MethodHandle valueToObject = MethodHandles.insertArguments(PART, 0, toObjectFunction(
                        annotated.valueClass(), building).asType(MethodType.methodType(Object.class, Object.class)));
                result = MethodHandles.filterArguments(
                        MethodHandles.insertArguments(annotated.constructor(), 1, Annotations.empty()), 0,
                        valueToObject);
            }
            case DataClassRecord dataClassRecord -> {

				// (Object[]):typeClass -> new TypeClass( params );
                result = dataClassRecord.constructor();

                DataClassField[] fields = dataClassRecord.fields();
                DataClassField carrier = dataClassRecord.annotationsCarrier().orElse(null);
                for (int fieldIndex = 0; fieldIndex < dataClassRecord.fields().length; fieldIndex++) {
                    if (fields[fieldIndex] == carrier) {
                        continue;
                    }
					DataClass fieldDataClass = fields[fieldIndex].dataClass();

                    // (Object[],int) -> values[int]:Object
                    MethodHandle arrayGetter = MethodHandles.arrayElementGetter(Object[].class);

                    // () -> fieldIndex
                    MethodHandle index = MethodHandles.constant(int.class, fieldIndex);

                    // (Object[]):Object -> (values[inputIndex]):Object
                    MethodHandle arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index);

					// (Object):Object -> value == null ? null : toObject(value)
					MethodHandle fieldToObject = MethodHandles.insertArguments(PART, 0, toObjectFunction(
							fieldDataClass, building).asType(MethodType.methodType(Object.class, Object.class)));

                    // (values[]):Object -> part(toObject, values[x])
                    arrayIndexGetter = MethodHandles.filterReturnValue(arrayIndexGetter, fieldToObject);

                    // (values[],int,Object):void -> values[int] = Object;
                    MethodHandle arraySetter = MethodHandles.arrayElementSetter(Object[].class);

                    // (values[],Object):void -> values[x] = Object;
                    MethodHandle arrayIndexSetter = MethodHandles.collectArguments(arraySetter, 1, index);

                    // (values[],Object[]):void -> values[x] = toObject(values[x]);
                    MethodHandle arrayValueSetter = MethodHandles.collectArguments(arrayIndexSetter, 1,
                            arrayIndexGetter);

                    int[] permuteInput = new int[2];
                    MethodHandle combined = MethodHandles.permuteArguments(arrayValueSetter,
                            MethodType.methodType(void.class, Object[].class), permuteInput);

                    result = MethodHandles.foldArguments(result, combined);

                }

                result = MethodHandles.filterArguments(result, 0, CHECKED.bindTo(dataClassRecord));
                result = result.asType(result.type().changeReturnType(dataClass.dataClass()));
            }
            case DataClassTuple tuple -> {
                DataClassElement[] elements = tuple.elements();
                MethodHandle[] elementsToObject = new MethodHandle[elements.length];
                for (int x = 0; x < elements.length; x++) {
                    elementsToObject[x] = toObjectFunction(elements[x].dataClass(), building);
                }
                try {
                    result = MethodHandles.lookup().findVirtual(ArrayToTupleBridge.class, "toObject",
                            MethodType.methodType(Object.class, Object[].class))
                            .bindTo(new ArrayToTupleBridge(tuple, elementsToObject));
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new DataBindException("failed to build bridge for tuple", e);
                }
                result = result.asType(MethodType.methodType(tuple.typeClass(), Object.class));
            }
            case DataClassArray dataArrayClass -> {

                // toObject( Object[] ):<array>
                try {

                    MethodHandle valueToData = toObjectFunction(dataArrayClass.arrayDataClass(), building);

                    ArrayToObjectBridge bridge = new ArrayToObjectBridge(dataArrayClass, valueToData);

                    result = MethodHandles.lookup().findVirtual(ArrayToObjectBridge.class, "toObject",
                            MethodType.methodType(Object.class, Object[].class)).bindTo(bridge);

                    result = result.asType(MethodType.methodType(dataArrayClass.typeClass(), Object.class));

                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new DataBindException("failed to build bridge for array", e);
                }
            }
            case DataClassMap dataMapClass -> {
                try {
                    EntriesToMapBridge bridge = new EntriesToMapBridge(dataMapClass,
                            toObjectFunction(dataMapClass.keyDataClass(), building),
                            toObjectFunction(dataMapClass.valueDataClass(), building));

                    result = MethodHandles.lookup().findVirtual(EntriesToMapBridge.class, "toObject",
                            MethodType.methodType(Object.class, Object[].class)).bindTo(bridge);

                    result = result.asType(MethodType.methodType(dataMapClass.typeClass(), Object.class));
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new DataBindException("failed to build bridge for map", e);
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
	 * A record's values line up by position with its fields on the wire -- every field but the annotations
	 * carrier, which the form has no place for -- so an array of any other length leaves a field without a value or
	 * a value without a field. A null is an absent value, which a required field refuses. Returns the values at
	 * their fields' indexes, the carrier holding no annotations, in a new array the fields are converted in: the
	 * caller's array is left as it was.
	 */
	private static Object[] checked(DataClassRecord record, Object[] values) throws DataBindException {
		DataClassField[] fields = record.fields();
		DataClassField carrier = record.annotationsCarrier().orElse(null);
		if (values.length != wireLength(record)) {
			throw new DataBindException(String.format("%s has %d fields but the array has length %d",
					record.typeClass().getName(), wireLength(record), values.length));
		}
		Object[] construct = new Object[fields.length];
		for (int x = 0, position = 0; x < fields.length; x++) {
			if (fields[x] == carrier) {
				construct[x] = Annotations.empty();
				continue;
			}
			Object value = values[position++];
			if (value == null && fields[x].isRequired()) {
				throw Checks.missingField(record, fields[x]);
			}
			construct[x] = value;
		}
		return construct;
	}

	private static int wireLength(DataClassRecord record) {
		return record.fields().length - (record.annotationsCarrier().isPresent() ? 1 : 0);
	}

	/** A write of a record holding no value for a required field, which a read of what it wrote would refuse. */
	private static void absent(DataClassRecord record, DataClassField field) throws DataBindException {
		throw Checks.missingField(record, field);
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
	private MethodHandle createToDataFunction(DataClass dataClass, Set<DataClass> building)
			throws DataBindException {

		MethodHandle toDataMethod = null;

        switch (dataClass) {
			case DataClassAtom dataClassAtom -> {
				// (typeClass):typeClass
				toDataMethod = MethodHandles.identity(dataClass.dataClass());
			}
			case DataClassAnnotated annotated -> {
				// (box):Object -> part(toData, box.value())
				toDataMethod = MethodHandles.filterReturnValue(annotated.value(),
						MethodHandles.insertArguments(PART, 0, toDataFunction(annotated.valueClass(), building)
								.asType(MethodType.methodType(Object.class, Object.class))));
			}
            case DataClassRecord dataClassRecord -> {

                // (int):Object[] -> new Object[int]
                MethodHandle createArray = MethodHandles.arrayConstructor(Object[].class);

                // (int):length -> wireLength
                MethodHandle index = MethodHandles.constant(int.class, wireLength(dataClassRecord));

                // ():Object[] -> new Object[wireLength]
                MethodHandle arrayCreate = MethodHandles.collectArguments(createArray, 0, index);

                // (Object[],serialClass):void -> getters(Object[],serialClass)
                MethodHandle getters = createProjectGetters(dataClassRecord, building);

                // (Object[],targetClass):void -> getters(Object[], project(targetClass))
                //MethodHandle projectGetters = MethodHandles.collectArguments(getters, 1, dataClassRecord.toData());

                // (Object):Object[] -> return getters(new Object[wireLength], serialiClass);
                toDataMethod = MethodHandles.collectArguments(getters, 0, arrayCreate);
            }
            case DataClassTuple tuple -> {
                DataClassElement[] elements = tuple.elements();
                MethodHandle[] elementsToData = new MethodHandle[elements.length];
                for (int x = 0; x < elements.length; x++) {
                    elementsToData[x] = toDataFunction(elements[x].dataClass(), building);
                }
                try {
                    toDataMethod = MethodHandles.lookup().findVirtual(TupleToArrayBridge.class, "toData",
                            MethodType.methodType(Object[].class, Object.class))
                            .bindTo(new TupleToArrayBridge(tuple, elementsToData))
                            .asType(MethodType.methodType(Object.class, tuple.typeClass()));
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new DataBindException("failed to build tuple bridge", e);
                }
            }
            case DataClassArray dataClassArray -> {

                try {

                    DataClassArray arrayClass = (DataClassArray) dataClass;

                    MethodHandle arrayToData = toDataFunction(arrayClass.arrayDataClass(), building);

                    ObjectToArrayBridge bridge = new ObjectToArrayBridge(arrayClass, arrayToData);

                    MethodHandle bridgeToData = MethodHandles.lookup().findVirtual(ObjectToArrayBridge.class, "toData",
                            MethodType.methodType(Object[].class, Object.class)).bindTo(bridge);

                    toDataMethod = bridgeToData.asType(MethodType.methodType(Object.class, dataClass.typeClass()));
                    // fieldBox = MethodHandles.collectArguments(bridgeToData, 0, fieldBox);
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new DataBindException("failed to build array bridge", e);
                }
            }
            case DataClassMap dataClassMap -> {
                try {
                    MapToEntriesBridge bridge = new MapToEntriesBridge(dataClassMap,
                            toDataFunction(dataClassMap.keyDataClass(), building),
                            toDataFunction(dataClassMap.valueDataClass(), building));

                    MethodHandle bridgeToData = MethodHandles.lookup().findVirtual(MapToEntriesBridge.class, "toData",
                            MethodType.methodType(Object[].class, Object.class)).bindTo(bridge);

                    toDataMethod = bridgeToData.asType(MethodType.methodType(Object.class, dataClass.typeClass()));
                } catch (NoSuchMethodException | IllegalAccessException e) {
                    throw new DataBindException("failed to build map bridge", e);
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
	private MethodHandle createProjectGetters(DataClassRecord dataClass, Set<DataClass> building)
			throws DataBindException {

		// (object[]):object[] -> return object[];
		MethodHandle identity = MethodHandles.identity(Object[].class);

		// (Object[], dataClass):object[] -> return object[];
		MethodHandle result = MethodHandles.dropArguments(identity, 1, dataClass.dataClass());

		DataClassField[] fields = dataClass.fields();
		DataClassField carrier = dataClass.annotationsCarrier().orElse(null);
		int outputIndex = 0;
		for (int x = 0; x < fields.length; x++) {

			DataClassField field = fields[x];
			if (field == carrier) {
				continue;
			}

			// (value[],x, v) -> value[x] = v
			MethodHandle arraySetter = MethodHandles.arrayElementSetter(Object[].class);

			// () -> outputIndex
			MethodHandle index = MethodHandles.constant(int.class, outputIndex++);

			// (value[],v) -> value[inputIndex] = v
			MethodHandle arrayIndexSetter = MethodHandles.collectArguments(arraySetter, 1, index);

			DataClass fieldDataClass = field.dataClass();

			// (<targettype>):<fieldtype> -> (Object) object.getter()
			MethodHandle fieldBox = field.accessor();

			// ( typeClass ):dataClass -> (dataClass) toData(typeClass)
			MethodHandle toDataFunction = toDataFunction(fieldDataClass, building);

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

			// ():void -> void, or absent(record, field) for a required field
			MethodHandle orElse = field.isRequired() ? MethodHandles.insertArguments(ABSENT, 0, dataClass, field) : noop;

			// (value[],object):void -> orElse();
			orElse = MethodHandles.dropArguments(orElse, 0, Object[].class, dataClass.dataClass());

			// (value[],object):void -> if(isPresent(object)) { value[inputIndex] = toData( object.getter() ) }
			MethodHandle checkAndSet = MethodHandles.guardWithTest(isPresentTest, arrayValueSetter, orElse);

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
		private final MethodHandle elementToData;

		public ObjectToArrayBridge(DataClassArray fieldDataClass, MethodHandle elementToData) {
			this.arrayClass = fieldDataClass;
			this.elementToData = elementToData.asType(MethodType.methodType(Object.class, Object.class));
		}

		@SuppressWarnings("unused")
		public Object[] toData(Object v) throws DataBindException {
			try {

				Object arrayData = v;
				int length = (int) arrayClass.size().invoke(arrayData);
				Object[] outputArray = new Object[length];
				Object iterator = arrayClass.iterator().invoke(arrayData);

				for (int x = 0; x < length; x++) {
					outputArray[x] = part(elementToData, arrayClass.get().invoke(arrayData, iterator));
				}

				return outputArray;

			} catch (DataBindException e) {
				throw e;
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
			this.typeMap = new ConcurrentHashMap<>();
		}

		@SuppressWarnings("unused")
		public Object[] toData(Object v) throws DataBindException {

			Object[] unionPair = new Object[2];
			if (v != null) {
				// A constant with a body is an instance of the body's class, and a member as its enum.
				Class<?> memberClass = v instanceof Enum<?> constant ? constant.getDeclaringClass() : v.getClass();
				unionPair[0] = memberClass.getName();

				ArrayFunctions function = typeMap.get(memberClass);
				if (function == null) {

					DataClass dataClass = context.getDescriptor(memberClass);

					if (!unionClass.isMemberType(dataClass.typeClass())) {
						throw new DataBindException("Class not of union type");
					}

					function = getFunctions(dataClass);
					typeMap.put(memberClass, function);
				}

				try {
					unionPair[1] = function.toArray.invoke(v);
				} catch (DataBindException e) {
					throw e;
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
			this.arrayToObject = arrayToObject.asType(MethodType.methodType(Object.class, Object.class));
		}

		@SuppressWarnings("unused")
		public Object toObject(Object[] s) throws DataBindException {
			try {
				Object[] inputArray = s;

				int length = inputArray.length;
				Object arrayData = arrayClass.constructor().invoke(length);
				Object iterator = arrayClass.iterator().invoke(arrayData);

				for (int x = 0; x < length; x++) {
					arrayClass.put().invoke(arrayData, iterator, part(arrayToObject, inputArray[x]));
				}

				return arrayData;
			} catch (DataBindException e) {
				throw e;
			} catch (Throwable e) {
				throw new DataBindException("Failed to convert array", e);
			}
		}
	}

	private static class TupleToArrayBridge {

		private final DataClassElement[] elements;
		private final MethodHandle[] elementsToData;

		TupleToArrayBridge(DataClassTuple tuple, MethodHandle[] elementsToData) {
			this.elements = tuple.elements();
			this.elementsToData = new MethodHandle[elementsToData.length];
			for (int x = 0; x < elementsToData.length; x++) {
				this.elementsToData[x] = elementsToData[x].asType(MethodType.methodType(Object.class, Object.class));
			}
		}

		@SuppressWarnings("unused")
		public Object[] toData(Object tuple) throws Throwable {
			Object[] values = new Object[elements.length];
			for (int x = 0; x < elements.length; x++) {
				values[x] = part(elementsToData[x], elements[x].accessor().invoke(tuple));
			}
			return values;
		}
	}

	private static class ArrayToTupleBridge {

		private final DataClassTuple tuple;
		private final MethodHandle[] elementsToObject;

		ArrayToTupleBridge(DataClassTuple tuple, MethodHandle[] elementsToObject) {
			this.tuple = tuple;
			this.elementsToObject = new MethodHandle[elementsToObject.length];
			for (int x = 0; x < elementsToObject.length; x++) {
				this.elementsToObject[x] =
						elementsToObject[x].asType(MethodType.methodType(Object.class, Object.class));
			}
		}

		@SuppressWarnings("unused")
		public Object toObject(Object[] values) throws Throwable {
			Checks.tuple(tuple, values);
			Object[] elements = new Object[values.length];
			for (int x = 0; x < values.length; x++) {
				elements[x] = part(elementsToObject[x], values[x]);
			}
			return tuple.constructor().invoke(elements);
		}
	}

	/**
	 * A map is an array of {@code [key, value]} entries rather than a {@code Map}: a key in array form may itself be
	 * an array, which hashes by identity, and {@link #toArray(DataClass, Object)} returns an array.
	 */
	private static class MapToEntriesBridge {

		private final DataClassMap mapClass;
		private final MethodHandle keyToData;
		private final MethodHandle valueToData;

		MapToEntriesBridge(DataClassMap mapClass, MethodHandle keyToData, MethodHandle valueToData) {
			this.mapClass = mapClass;
			this.keyToData = keyToData;
			this.valueToData = valueToData;
		}

		@SuppressWarnings("unused")
		public Object[] toData(Object map) throws DataBindException {
			try {
				Object[] entries = new Object[(int) mapClass.size().invoke(map)];
				Object iterator = mapClass.iterator().invoke(map);
				Object entry;
				for (int x = 0; (entry = mapClass.next().invoke(iterator)) != null; x++) {
					entries[x] = new Object[] { part(keyToData, mapClass.key().invoke(entry)),
							part(valueToData, mapClass.value().invoke(entry)) };
				}
				return entries;
			} catch (DataBindException e) {
				throw e;
			} catch (Throwable e) {
				throw new DataBindException("Failed to convert map", e);
			}
		}
	}

	private static class EntriesToMapBridge {

		private final DataClassMap mapClass;
		private final MethodHandle keyToObject;
		private final MethodHandle valueToObject;

		EntriesToMapBridge(DataClassMap mapClass, MethodHandle keyToObject, MethodHandle valueToObject) {
			this.mapClass = mapClass;
			this.keyToObject = keyToObject;
			this.valueToObject = valueToObject;
		}

		@SuppressWarnings("unused")
		public Object toObject(Object[] entries) throws DataBindException {
			if (entries == null) {
				return null;
			}
			try {
				Object map = mapClass.constructor().invoke(entries.length);
				for (Object entry : entries) {
					Object[] pair = (Object[]) entry;
					mapClass.put().invoke(map, part(keyToObject, pair[0]), part(valueToObject, pair[1]));
				}
				return map;
			} catch (DataBindException e) {
				throw e;
			} catch (Throwable e) {
				throw new DataBindException("Failed to convert map", e);
			}
		}
	}

	/** An absent component, a null element and a map's null key or value are each a null no conversion takes. */
	private static Object part(MethodHandle convert, Object value) throws Throwable {
		return value == null ? null : convert.invoke(value);
	}

	private class UnionToObjectBridge {
		private final DataClassUnion unionClass;
		private final Map<String, ArrayFunctions> typeMap;

		public UnionToObjectBridge(DataClassUnion unionClass) {
			this.unionClass = unionClass;
			this.typeMap = new ConcurrentHashMap<>();
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

						DataClass dataClass = context.getDescriptor(Checks.candidate(unionClass, unionType));
						if (!unionClass.isMemberType(dataClass.typeClass())) {
							throw new DataBindException("Class not of union type");
						}

						function = getFunctions(dataClass);
						typeMap.put(unionType, function);
					}

					value = function.toObject.invoke(unionValue);
				} catch (DataBindException e) {
					throw e;
				} catch (Throwable e) {
					throw new DataBindException("Failed to convert class", e);
				}
			}

			return value;
		}

	}
}
