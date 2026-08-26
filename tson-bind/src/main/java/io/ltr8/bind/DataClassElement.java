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
import java.util.function.Supplier;
import io.ltr8.bind.internal.Memoized;

/**
 * One positional slot of a {@link DataClassTuple} -- the tuple analogue of {@link DataClassField},
 * pared down to what a fixed-arity, unnamed, immutable-by-construction slot actually needs: no
 * name, no {@code isRequired}/{@code isPresent} (a record component is always present -- there's no
 * such thing as a partially-constructed record), and no setter (records are immutable; the whole
 * tuple is rebuilt at once via {@link DataClassTuple#constructor()}).
 */
public class DataClassElement {

	// the slot's position -- the same index as its constructor argument / RecordComponent order.
	private final int index;

	// the DataClass for this slot's own type.
	/**
	 * Kept memoised rather than resolved, because a cyclic type graph has one edge that cannot be resolved
	 * while the descriptor it points back to is still being built. Known at construction on every other edge,
	 * which is all of them but that one -- see {@code DataBindContext.componentSource}.
	 */
	private final Memoized<DataClass> dataClass;



	// <value> accessor( <tuple> ); -- the component's own declared type, not forced to Object.
	private final MethodHandle accessor;

	/** The same, for a component on a cycle in the type graph -- see {@code DataBindContext.componentSource}. */
	public DataClassElement(int index, Supplier<DataClass> dataClass, MethodHandle accessor) {
		this.index = index;
		this.dataClass = Memoized.deferred(dataClass);
		this.accessor = accessor;
	}

	public DataClassElement(int index, DataClass dataClass, MethodHandle accessor) {
		this.index = index;
		this.dataClass = Memoized.of(dataClass);
		this.accessor = accessor;
	}

	public int index() {
		return index;
	}

	public DataClass dataClass() {
		return dataClass.get();
	}

	/**
	 * @return a MethodHandle for extracting this slot's value from a tuple instance. accessor(
	 *         tuple ):value;
	 */
	public MethodHandle accessor() {
		return accessor;
	}

	@Override
	public String toString() {
		return "DataClassElement [index=" + index + ", type=" + dataClass + "]";
	}
}
