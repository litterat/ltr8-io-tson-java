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
package io.ltr8.bind;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;

/**
 * Represents a Tuple data class -- a heterogeneous, fixed-arity product accessed positionally, the
 * shape the meta-kernel's own {@code tuple} constructor describes ({@code ~product & {
 * access_pattern: INDEX, size_type: FIXED, elements: [...] } }): {@link DataClassRecord}'s
 * heterogeneity (each slot has its own type), but {@link DataClassArray}'s index-based access
 * instead of named fields.
 * <p>
 * Unlike {@code DataClassArray}/{@code DataClassMap}, there's no {@code size()} MethodHandle --
 * arity is fixed at the Java type level ({@code elements().length}), not runtime state to query --
 * and no {@code put()}/iterator: today's only source is a genuine Java {@code record} (see {@code
 * io.ltr8.annotation.Tuple}), which is built all at once via its canonical constructor, the same
 * way {@link DataClassRecord} is, not filled in one slot at a time. Reading is a plain per-slot
 * {@link DataClassElement#accessor()} call, not a combined {@code get(tuple, pos)} MethodHandle --
 * each slot has its own type, so there's nothing to gain by forcing them through one dispatch point
 * the way {@code DataClassArray}'s single element type lets {@code get(array, iter)} work.
 * <p>
 * Constructing from a positional value array:
 * <p>
 * <pre>
 * DataClassTuple tupleClass = (DataClassTuple) dataClass;
 * DataClassElement[] elements = tupleClass.elements();
 *
 * Object[] values = new Object[elements.length];
 * for (int i = 0; i &lt; elements.length; i++) {
 * 	values[i] = toObject(sourceElements.get(i), elements[i].dataClass());
 * }
 * Object tuple = tupleClass.constructor().invoke(values);
 * </pre>
 */
public class DataClassTuple extends DataClass {

	// (Object[]):tuple
	private final MethodHandle constructor;

	// one slot per positional element, in constructor-argument order.
	private final DataClassElement[] elements;

	public DataClassTuple(Class<?> targetType, MethodHandle constructor, DataClassElement[] elements) {
		super(targetType);
		this.constructor = constructor;
		this.elements = elements;
	}

	/**
	 * @return A MethodHandle that builds the tuple from its positional values. constructor(
	 *         Object[] values ):tuple;
	 */
	public MethodHandle constructor() {
		return constructor;
	}

	/**
	 * @return The tuple's slots, in constructor-argument order. Its length is the tuple's fixed
	 *         arity.
	 */
	public DataClassElement[] elements() {
		return elements;
	}

	@Override
	public String toString() {
		return "DataClassTuple [ typeClass=" + typeClass().getName() + ", elements=" + Arrays.toString(elements)
				+ "]";
	}

}
