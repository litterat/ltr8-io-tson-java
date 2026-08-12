/*
 * Copyright (c) 2020, Litterat Pty Ltd. All Rights Reserved.
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
package io.ltr8.bind;

import java.lang.invoke.MethodHandle;
import java.util.Optional;
import java.util.Arrays;

/**
 * A DataClassRecord provides a descriptor for record data classes projected/embedded pair for use
 * in serialization libraries.
 */
public class DataClassRecord extends DataClass {

	private final boolean isMutable;

	// Empty constructor/acquirer for data object mutable objects.
	private final MethodHandle creator;

	// Constructor for the data object.
	private final MethodHandle constructor;

	// All fields in the projected class.
	private final DataClassField[] fields;

	// The one field (if any) whose declared type is Annotations -- see annotationsCarrier().
	private final DataClassField annotationsCarrier;

	public DataClassRecord( Class<?> targetType, DataClassBridge bridge, boolean isMutable, MethodHandle creator, MethodHandle constructor,  DataClassField[] fields) {
		this(targetType, bridge, isMutable, creator, constructor, fields, null);
	}

	public DataClassRecord(Class<?> targetType, DataClassBridge bridge, boolean isMutable, MethodHandle creator,
			MethodHandle constructor, DataClassField[] fields, DataClassField annotationsCarrier) {
		super(targetType, bridge);
		this.fields = fields;
		this.isMutable = isMutable;
		this.creator = creator;
		this.constructor = constructor;
		this.annotationsCarrier = annotationsCarrier;
	}

	public boolean isMutable() {
		return isMutable;
	}

	public MethodHandle creator() {
		return creator;
	}

	/**
	 * @return A MethodHandle that has the signature T constructor(Object[] values).
	 */
	public MethodHandle constructor() {
		return constructor;
	}

	/**
	 * @return The list of fields and their types returned by the embed function.
	 */
	public DataClassField[] fields() {
		return fields;
	}

	/**
	 * The field, if any, that receives this value's own TSON wire-format annotations rather than being bound
	 * from an authored field -- the one component whose declared type is {@code io.ltr8.annotation.Annotations}
	 * (at most one; a second is an analysis error). Absent for the overwhelming majority of classes.
	 *
	 * <p>It appears in {@link #fields()} too, because it still occupies a constructor slot that has to be
	 * filled. <b>Every caller iterating {@code fields()} must exclude it</b>: it is not matched by name against
	 * anything on the wire, and its {@code dataClass()} is {@code null} because there is no authored value to
	 * resolve a descriptor for. Asking the record which field it is beats asking each field whether it is the
	 * one -- the answer is settled once, during analysis, and a reader needs it before it starts matching
	 * rather than while walking.
	 *
	 * @return the carrier field, or empty when this class opted out by simply not declaring one
	 */
	public Optional<DataClassField> annotationsCarrier() {
		return Optional.ofNullable(annotationsCarrier);
	}

	@Override
	public String toString() {
		return "DataClassRecord [ typeClass=" + typeClass().getName() + ", fields=" + Arrays.toString(fields) + "]";
	}

}
