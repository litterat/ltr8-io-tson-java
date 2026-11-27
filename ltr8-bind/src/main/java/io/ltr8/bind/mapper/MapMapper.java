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
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Sample showing how to use the library to convert an Object to/from {@code Map<String, Object>}. Each key
 * in the returned map has the name of the field. Union types have an extra field called "type" which
 * provides the class name of the type of value recorded. A map component is a map of its keys and values, each in
 * this form, in the order the component's map iterates in.
 * <p>
 * A read missing a required field throws the {@link DataBindException} that names the record and the field,
 * however deep the record sits: no enclosing record, list, map or union wraps it.
 */
public class MapMapper {

	private final DataBindContext context;

	public MapMapper(DataBindContext context) {
		this.context = context;
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> toMap(Object object) throws DataBindException {
		Objects.requireNonNull(object);

		return (Map<String, Object>) toMap(context.getDescriptor(object.getClass()), object);
	}

	@SuppressWarnings("unchecked")
	public Object toMap(DataClass dataClass, Object object) throws DataBindException {

		int fieldIndex = 0;

		try {
			Object v = null;

            // If there's a bridge convert it.
            if (dataClass.bridge().isPresent()) {
                DataClassBridge bridge = dataClass.bridge().get();
                object = bridge.toData().invoke(object);
            }

            switch (dataClass) {
                case DataClassAtom dataAtom -> {
                    v = object;
                }
                case DataClassRecord dataRecord -> {
                    //Object data = dataRecord.toData().invoke(object);
                    Map<String, Object> map = new HashMap<>();
                    DataClassField[] fields = dataRecord.fields();
                    for (fieldIndex = 0; fieldIndex < fields.length; fieldIndex++) {
                        DataClassField field = fields[fieldIndex];

                        if (field.isPresent(object)) {
                            DataClass fieldDataClass = field.dataClass();
                            Object fv = toMap(fieldDataClass, field.get(object));
                            map.put(field.name(), fv);
                        }
                    }

                    v = map;
                }
                case DataClassArray arrayClass -> {

                    int length = (int) arrayClass.size().invoke(object);
                    Object[] outputArray = new Object[length];
                    Object iterator = arrayClass.iterator().invoke(object);

                    DataClass arrayDataClass = arrayClass.arrayDataClass();
                    for (int x = 0; x < length; x++) {
                        Object av = arrayClass.get().invoke(object, iterator);
                        outputArray[x] = partToMap(arrayDataClass, av);
                    }

                    v = outputArray;
                }
                case DataClassMap mapClass -> {
                    Map<Object, Object> map = new LinkedHashMap<>();
                    Object iterator = mapClass.iterator().invoke(object);
                    Object entry;
                    while ((entry = mapClass.next().invoke(iterator)) != null) {
                        map.put(partToMap(mapClass.keyDataClass(), mapClass.key().invoke(entry)),
                                partToMap(mapClass.valueDataClass(), mapClass.value().invoke(entry)));
                    }
                    v = map;
                }
                case DataClassUnion unionClass -> {

                    // Make sure this class is a member of the union before writing it.
                    DataClass unionInstanceClass = context.getDescriptor(object.getClass());
                    if (!unionClass.isMemberType(unionInstanceClass.typeClass())) {
                        throw new IllegalArgumentException(
                                String.format("Class '%s' not a member of union type.", object.getClass().getName()));
                    }

                    // A union needs to know the type being written so it can be picked up by
                    // the reader later.
                    v = toMap(unionInstanceClass, object);
                    if (v instanceof @SuppressWarnings("rawtypes")Map baseMap) {

                        // Using the full class name here as an example. A better/more complete implementation
                        // would use a schema based name.
                        baseMap.put("type", object.getClass().getName());
                    }
                }
                case null, default -> throw new IllegalArgumentException("Unknown data class");
            }
			return v;
		} catch (Throwable t) {
            throw new DataBindException(String.format("Failed to convert %s to Map", dataClass.typeClass()), t);
		}
	}

	public Object toObject(Class<?> clss, Map<String, Object> map) throws DataBindException {

		Objects.requireNonNull(clss);
		Objects.requireNonNull(map);

		return toObject(context.getDescriptor(clss), map);
	}

	@SuppressWarnings("unchecked")
	public Object toObject(DataClass dataClass, Object data) throws DataBindException {

		int fieldIndex = 0;

		try {
			Object v = null;

            switch (dataClass) {

                case DataClassAtom dataClassAtom -> {
                    v = data;
                }
                case DataClassRecord dataRecord -> {
                    Map<String, Object> map = (Map<String, Object>) data;
                    DataClassField[] fields = dataRecord.fields();
                    Object[] construct = new Object[fields.length];
                    DataClassField carrier = dataRecord.annotationsCarrier().orElse(null);
                    for (fieldIndex = 0; fieldIndex < dataRecord.fields().length; fieldIndex++) {
                        DataClassField field = fields[fieldIndex];

                        Object fv = map.get(field.name());

                        if (fv != null) {
                            fv = toObject(field.dataClass(), fv);
                        } else if (field.isRequired() && field != carrier) {
                            throw new DataBindException(String.format("%s is missing its required field '%s'",
                                    dataRecord.typeClass().getName(), field.name()));
                        }
                        construct[fieldIndex] = fv;
                    }

                    v = dataRecord.constructor().invoke(construct);
                    // v = dataRecord.toObject().invoke(v);
                }
                case DataClassArray arrayClass -> {

                    Object[] inputArray = (Object[]) data;

                    int length = inputArray.length;
                    Object arrayData = arrayClass.constructor().invoke(length);
                    Object iterator = arrayClass.iterator().invoke(arrayData);

                    DataClass arrayDataClass = arrayClass.arrayDataClass();

                    for (Object o : inputArray) {
                        arrayClass.put().invoke(arrayData, iterator, partToObject(arrayDataClass, o));
                    }

                    v = arrayData;
                }
                case DataClassMap mapClass -> {
                    Map<Object, Object> input = (Map<Object, Object>) data;
                    Object map = mapClass.constructor().invoke(input.size());
                    for (Map.Entry<Object, Object> entry : input.entrySet()) {
                        mapClass.put().invoke(map, partToObject(mapClass.keyDataClass(), entry.getKey()),
                                partToObject(mapClass.valueDataClass(), entry.getValue()));
                    }
                    v = map;
                }
                case DataClassUnion unionClass -> {

                    if (data instanceof Map) {

                        Map<String, Object> map = (Map<String, Object>) data;

                        // A tagged union uses "type" for the class name.
                        String type = (String) map.get("type");

                        DataClass instantType = context.getDescriptor(Class.forName(type));
                        if (!unionClass.isMemberType(instantType.typeClass())) {
                            throw new DataBindException(String.format("instance type '%s' not of expected union type '%s'",
                                    instantType.typeClass().getName(), unionClass.typeClass().getName()));
                        }

                        v = toObject(instantType, data);
                    } else {
                        DataClass unionType = context.getDescriptor(data.getClass());

                        v = toObject(unionType, data);
                    }
                }
                case null, default -> throw new IllegalArgumentException("unrecognised type");
            }

            if (dataClass.bridge().isPresent()) {
                v = dataClass.bridge().get().toObject().invoke(v);
            }

            return v;
		} catch (DataBindException e) {
			throw e;
		} catch (Throwable t) {
			throw new DataBindException(String.format("Failed to convert Map to %s.", dataClass.typeClass()), t);
		}

	}

	/** A null element and a map's null key or value are each a null that no data class converts. */
	private Object partToMap(DataClass dataClass, Object part) throws DataBindException {
		return part == null ? null : toMap(dataClass, part);
	}

	private Object partToObject(DataClass dataClass, Object part) throws DataBindException {
		return part == null ? null : toObject(dataClass, part);
	}
}
