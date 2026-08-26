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
 * Represents a Map data class -- a keyed collection, distinct from {@link DataClassArray}'s
 * sequential one. This provides an interface made up of MethodHandles to interact with the map
 * implementation, following the same shape as {@link DataClassArray} but with a key/value pair per
 * entry instead of a single element.
 * <p>
 * Unlike an array's single {@code get(array, iter):value}, a map entry carries two values that must
 * come from the *same* iteration step without advancing twice -- so iteration here is a two-stage
 * {@code next(iter):entry} (advance once, or {@code null} once exhausted) followed by
 * {@code key(entry):key}/{@code value(entry):value} (plain extraction, safe to call any number of
 * times on the same entry).
 * <p>
 * Extracting the entries from a map object:
 * <pre>
 * DataClassMap mapClass = (DataClassMap) dataClass;
 *
 * Object iterator = mapClass.iterator().invoke(mapData);
 * Object entry;
 * while ((entry = mapClass.next().invoke(iterator)) != null) {
 * 	Object k = mapClass.key().invoke(entry);
 * 	Object v = mapClass.value().invoke(entry);
 * 	...
 * }
 * </pre>
 * <p>
 * Instantiating and loading entries into the map:
 * <pre>
 * DataClassMap mapClass = (DataClassMap) dataClass;
 *
 * Object mapData = mapClass.constructor().invoke(size);
 * for (each source entry) {
 * 	mapClass.put().invoke(mapData, key, value);
 * }
 * v = mapData;
 * </pre>
 *
 * The MethodHandle signatures are:
 * <ul>
 * <li>constructor( int size ):Map;
 * <li>size( Map ):int;
 * <li>iterator( Map ):Iterator;
 * <li>next( Iterator ):Entry (or null once exhausted);
 * <li>key( Entry ):key;
 * <li>value( Entry ):value;
 * <li>put( Map, key, value ):void;
 * </ul>
 *
 */
public class DataClassMap extends DataClass {

	// data class for the map's key type.
	/**
	 * Kept memoised rather than resolved, because a cyclic type graph has one edge that cannot be resolved
	 * while the descriptor it points back to is still being built. Known at construction on every other edge,
	 * which is all of them but that one -- see {@code DataBindContext.componentSource}.
	 */
	private final Memoized<DataClass> keyDataClass;



	// data class for the map's value type.
	/**
	 * Kept memoised rather than resolved, because a cyclic type graph has one edge that cannot be resolved
	 * while the descriptor it points back to is still being built. Known at construction on every other edge,
	 * which is all of them but that one -- see {@code DataBindContext.componentSource}.
	 */
	private final Memoized<DataClass> valueDataClass;



	// <map> constructor( int size );
	private final MethodHandle constructor;

	// int size( <map> );
	private final MethodHandle size;

	// <iter> iterator( <map> );
	private final MethodHandle iterator;

	// <entry> next( <iter> ); -- null once exhausted.
	private final MethodHandle next;

	// <key> key( <entry> );
	private final MethodHandle key;

	// <value> value( <entry> );
	private final MethodHandle value;

	// void put( <map>, <key>, <value> );
	private final MethodHandle put;


	/** The same, for a component on a cycle in the type graph -- see {@code DataBindContext.componentSource}. */
	public DataClassMap(Class<?> targetType, Supplier<DataClass> keyDataClass,
			Supplier<DataClass> valueDataClass, MethodHandle constructor, MethodHandle size,
			MethodHandle iterator, MethodHandle next, MethodHandle key, MethodHandle value, MethodHandle put) {
		super(targetType);

		this.keyDataClass = Memoized.deferred(keyDataClass);
		this.valueDataClass = Memoized.deferred(valueDataClass);
		this.constructor = constructor;
		this.size = size;
		this.iterator = iterator;
		this.next = next;
		this.key = key;
		this.value = value;
		this.put = put;
	}

	public DataClassMap(Class<?> targetType, DataClass keyDataClass, DataClass valueDataClass,
			MethodHandle constructor, MethodHandle size, MethodHandle iterator, MethodHandle next,
			MethodHandle key, MethodHandle value, MethodHandle put) {
		super(targetType);

		this.keyDataClass = Memoized.of(keyDataClass);
		this.valueDataClass = Memoized.of(valueDataClass);
		this.constructor = constructor;
		this.size = size;
		this.iterator = iterator;
		this.next = next;
		this.key = key;
		this.value = value;
		this.put = put;
	}


	/**
	 * @return The DataClass type for the map's keys.
	 */
	public DataClass keyDataClass() {
		return keyDataClass.get();
	}

	/**
	 * @return The DataClass type for the map's values.
	 */
	public DataClass valueDataClass() {
		return valueDataClass.get();
	}

	/**
	 * @return A MethodHandle that creates the map. constructor(int size):type;
	 */
	public MethodHandle constructor() {
		return constructor;
	}

	/**
	 * @return a MethodHandle that returns the number of entries in the map. size( map ):int;
	 */
	public MethodHandle size() {
		return size;
	}

	/**
	 * @return a MethodHandle that returns an entry iterator to be used with next(). iterator( map
	 *         ):iter;
	 */
	public MethodHandle iterator() {
		return iterator;
	}

	/**
	 * @return a MethodHandle that advances the iterator and returns the next entry, or {@code null}
	 *         once exhausted. next( iter ):entry;
	 */
	public MethodHandle next() {
		return next;
	}

	/**
	 * @return a MethodHandle for extracting an entry's key. key( entry ):key;
	 */
	public MethodHandle key() {
		return key;
	}

	/**
	 * @return a MethodHandle for extracting an entry's value. value( entry ):value;
	 */
	public MethodHandle value() {
		return value;
	}

	/**
	 * @return a MethodHandle for adding an entry to the map. put( map, key, value ):void;
	 */

	public MethodHandle put() {
		return put;
	}

	/** A held descriptor's class name, without pulling one that is still deferred. */
	private static String shown(Memoized<DataClass> held) {
		DataClass known = held.peek();
		return known != null ? known.typeClass().getName() : "<deferred>";
	}

	@Override
	public String toString() {
		return "DataClassMap [ typeClass=" + typeClass().getName() + ", keyDataClass="
				+ shown(keyDataClass) + ", valueDataClass=" + shown(valueDataClass)
				+ "]";
	}

}
