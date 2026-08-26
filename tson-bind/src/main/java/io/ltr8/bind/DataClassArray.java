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
import java.util.function.Supplier;
import io.ltr8.bind.internal.Memoized;

/**
 * 
 * Represents an Array data class. The Array could be implemented by either a Java array or
 * collection. This provides an interface made up of MethodHandles to interact with the array
 * implementation.
 * <p>
 * Extracting the values from an array object:
 * <pre>{@code
 * DataClassArray arrayClass = (DataClassArray) dataClass;
 *
 * int length = (int) arrayClass.size().invoke(arrayData);
 * Object[] outputArray = new Object[length];
 * Object iterator = arrayClass.iterator().invoke(arrayData);
 *
 * DataClassRecord arrayDataClass = arrayClass.arrayDataClass();
 *
 * for (int x = 0; x < length; x++) {
 *     Object av = arrayClass.get().invoke(iterator, arrayData);
 *     outputArray[x] = toMap(arrayDataClass, av);
 * }
 * }</pre>
 * <p>
 * Instantiating and loading values to the array:
 * <pre>{@code
 * DataClassArray arrayClass = (DataClassArray) dataClass;
 * Object[] inputArray = (Object[]) data;
 *
 * int length = inputArray.length;
 * Object arrayData = arrayClass.constructor().invoke(length);
 * Object iterator = arrayClass.iterator().invoke(arrayData);
 *
 * DataClassRecord arrayDataClass = arrayClass.arrayDataClass();
 *
 * for (int x = 0; x < length; x++) {
 *     arrayClass.put().invoke(iterator, arrayData, toObject(arrayDataClass, inputArray[x]));
 * }
 *
 * v = arrayData;
 * }</pre>
 *
 * The MethodHandle signatures are:
 * <ul>
 * <li>constructor( int size ):Array;
 * <li>size( Array ):int;
 * <li>iterator( Array ):Iterator;
 * <li>put( Iterator, Array, Value ):void;
 * <li>get( Iterator, Array ):value;
 * </ul>
 * 
 */
public class DataClassArray extends DataClass {

	// data class.
	/**
	 * Kept memoised rather than resolved, because a cyclic type graph has one edge that cannot be resolved
	 * while the descriptor it points back to is still being built. Known at construction on every other edge,
	 * which is all of them but that one -- see {@code DataBindContext.componentSource}.
	 */
	private final Memoized<DataClass> arrayDataClass;



	// <array> constructor( int size );
	private final MethodHandle constructor;

	// int size( <array> );
	private final MethodHandle size;

	// <iter> iterator( <array> );
	private final MethodHandle iterator;

	// void put( <array>, <iter>, <value> );
	private final MethodHandle put;

	// <value> get( <array>, <iter> );
	private final MethodHandle get;

	/** The same, for an element type on a cycle in the type graph -- see {@link #arrayDataClass}. */
	public DataClassArray(Class<?> targetType, Supplier<DataClass> arrayDataClassSource,
			MethodHandle constructor, MethodHandle size, MethodHandle iterator, MethodHandle get, MethodHandle put)
			throws NoSuchMethodException, IllegalAccessException {
		super(targetType);

		this.arrayDataClass = Memoized.deferred(arrayDataClassSource);
		this.constructor = constructor;
		this.iterator = iterator;
		this.size = size;
		this.get = get;
		this.put = put;
	}

	public DataClassArray(Class<?> targetType, DataClass arrayDataClass,
			MethodHandle constructor, MethodHandle size, MethodHandle iterator, MethodHandle get, MethodHandle put)
			throws NoSuchMethodException, IllegalAccessException {
		super(targetType);

		this.arrayDataClass = Memoized.of(arrayDataClass);
		this.constructor = constructor;
		this.iterator = iterator;
		this.size = size;
		this.get = get;
		this.put = put;
	}

	/**
	 * @return A MethodHandle that creates the array. constructor(int size):type;
	 */
	public MethodHandle constructor() {
		return constructor;
	}

	/**
	 * @return The DataClass type for the array.
	 */
	public DataClass arrayDataClass() {
		return arrayDataClass.get();
	}

	/**
	 * 
	 * @return a MethodHandle that returns the size of the array. size( array ):int;
	 */
	public MethodHandle size() {
		return this.size;
	}

	/**
	 * @return a MethodHandle that returns an iterator to be used with put/get MethodHandles. iterator(
	 *         array ):iter;
	 */
	public MethodHandle iterator() {
		return this.iterator;
	}

	/**
	 * @return a MethodHandle for adding values to the array. put( array, iter, value ):void;
	 */
	public MethodHandle put() {
		return this.put;
	}

	/**
	 * @return a MethodHandle for getting values from the array. get( array, iter ):value;
	 */
	public MethodHandle get() {
		return this.get;
	}

	/** A held descriptor's class name, without pulling one that is still deferred. */
	private static String shown(Memoized<DataClass> held) {
		DataClass known = held.peek();
		return known != null ? known.typeClass().getName() : "<deferred>";
	}

	@Override
	public String toString() {
		return "DataClassArray [ typeClass=" + typeClass().getName() + ", arrayDataClass="
				+ shown(arrayDataClass) + "]";
	}

}
