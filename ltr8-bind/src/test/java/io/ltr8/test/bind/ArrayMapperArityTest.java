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
package io.ltr8.test.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.test.data.MapperShapes.HoldsTriple;
import io.ltr8.test.data.MapperShapes.Triple;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** An {@link ArrayMapper} reads a record from an array holding exactly one value per field. */
class ArrayMapperArityTest {

	private final ArrayMapper arrays = new ArrayMapper(DataBindContext.builder().build());

	@Test
	void anArrayOfOneValuePerFieldReads() throws Throwable {
		Triple triple = new Triple(1, "b", 3L);
		assertAll(
				() -> assertArrayEquals(new Object[] { 1, "b", 3L }, arrays.toArray(triple)),
				() -> assertEquals(triple, arrays.toObject(Triple.class, new Object[] { 1, "b", 3L })));
	}

	@Test
	void anArrayShorterThanTheFieldsIsRefused() {
		DataBindException refused = assertThrows(DataBindException.class,
				() -> arrays.toObject(Triple.class, new Object[] { 1, "b" }));
		assertEquals(Triple.class.getName() + " has 3 fields but the array has length 2",
				refused.getMessage());
	}

	@Test
	void anArrayLongerThanTheFieldsIsRefused() {
		DataBindException refused = assertThrows(DataBindException.class,
				() -> arrays.toObject(Triple.class, new Object[] { 1, "b", 3L, "extra" }));
		assertEquals(Triple.class.getName() + " has 3 fields but the array has length 4",
				refused.getMessage());
	}

	@Test
	void anEmptyArrayIsRefused() {
		DataBindException refused = assertThrows(DataBindException.class,
				() -> arrays.toObject(Triple.class, new Object[] {}));
		assertEquals(Triple.class.getName() + " has 3 fields but the array has length 0",
				refused.getMessage());
	}

	@Test
	void aNestedRecordOfTheWrongLengthIsRefusedNamingItsOwnClass() {
		DataBindException shorter = assertThrows(DataBindException.class,
				() -> arrays.toObject(HoldsTriple.class, new Object[] { "n", new Object[] { 1 } }));
		DataBindException longer = assertThrows(DataBindException.class,
				() -> arrays.toObject(HoldsTriple.class, new Object[] { "n", new Object[] { 1, "b", 3L, 4 } }));
		assertAll(
				() -> assertEquals(Triple.class.getName() + " has 3 fields but the array has length 1",
						shorter.getMessage()),
				() -> assertEquals(Triple.class.getName() + " has 3 fields but the array has length 4",
						longer.getMessage()));
	}
}
