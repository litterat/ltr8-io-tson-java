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
import io.ltr8.bind.DataClass;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.MapperShapes.HoldsPair;
import io.ltr8.test.data.MapperShapes.ListOfPairs;
import io.ltr8.test.data.MapperShapes.NestedPair;
import io.ltr8.test.data.MapperShapes.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A {@code @Tuple} record is an array of its elements, in order, through both mappers: as a root value, a
 * component, a list element, and inside another tuple.
 */
class TupleMapperTest {

	private static final String PAIR = Pair.class.getName();

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	private static final List<Object> VALUES = List.of(
			new Pair("n", 2),
			new Pair(null, 2),
			new NestedPair(new Pair("n", 2), 3L),
			new NestedPair(null, 3L),
			new HoldsPair("l", new Pair("n", 2)),
			new HoldsPair("l", null),
			new ListOfPairs(Arrays.asList(new Pair("a", 1), null, new Pair(null, 2))));

	@Test
	void aTupleRoundTripsThroughBothMappers() {
		assertAll(VALUES.stream().flatMap(value -> {
			Class<?> type = value.getClass();
			return Stream.of(
					() -> assertEquals(value, maps.toObject(descriptor(type), maps.toMap(descriptor(type), value)),
							"map " + value),
					() -> assertEquals(value, arrays.toObject(type, arrays.toArray(value)), "array " + value));
		}));
	}

	@Test
	void anArrayMapperWritesATupleAsAnArrayOfItsElements() {
		assertAll(
				() -> assertArrayEquals(new Object[] { "n", 2 }, arrays.toArray(new Pair("n", 2))),
				() -> assertArrayEquals(new Object[] { null, 2 }, arrays.toArray(new Pair(null, 2))),
				() -> assertArrayEquals(new Object[] { new Object[] { "n", 2 }, 3L },
						arrays.toArray(new NestedPair(new Pair("n", 2), 3L))),
				() -> assertArrayEquals(new Object[] { "l", new Object[] { "n", 2 } },
						arrays.toArray(new HoldsPair("l", new Pair("n", 2)))),
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { "a", 1 }, null } },
						arrays.toArray(new ListOfPairs(Arrays.asList(new Pair("a", 1), null)))));
	}

	@Test
	void aMapMapperWritesATupleAsAnArrayOfItsElements() {
		assertAll(
				() -> assertArrayEquals(new Object[] { "n", 2 },
						(Object[]) maps.toMap(descriptor(Pair.class), new Pair("n", 2))),
				() -> assertArrayEquals(new Object[] { new Object[] { "n", 2 }, 3L },
						(Object[]) maps.toMap(descriptor(NestedPair.class), new NestedPair(new Pair("n", 2), 3L))),
				() -> assertArrayEquals(new Object[] { "n", 2 },
						(Object[]) maps.toMap(new HoldsPair("l", new Pair("n", 2))).get("pair")),
				() -> assertEquals("l", maps.toMap(new HoldsPair("l", new Pair("n", 2))).get("label")),
				() -> assertArrayEquals(new Object[] { new Object[] { "a", 1 }, null },
						(Object[]) maps.toMap(new ListOfPairs(Arrays.asList(new Pair("a", 1), null))).get("pairs")));
	}

	@Test
	void anArrayOfTheWrongLengthIsRefused() {
		String shorter = PAIR + " has 2 elements but the array has length 1";
		String longer = PAIR + " has 2 elements but the array has length 3";
		assertAll(
				refused(shorter, () -> arrays.toObject(Pair.class, new Object[] { "n" })),
				refused(longer, () -> arrays.toObject(Pair.class, new Object[] { "n", 2, 3 })),
				refused(shorter, () -> arrays.toObject(HoldsPair.class, new Object[] { "l", new Object[] { "n" } })),
				refused(shorter, () -> arrays.toObject(ListOfPairs.class,
						new Object[] { new Object[] { new Object[] { "n" } } })),
				refused(shorter, () -> maps.toObject(descriptor(Pair.class), new Object[] { "n" })),
				refused(longer, () -> maps.toObject(descriptor(Pair.class), new Object[] { "n", 2, 3 })),
				refused(shorter, () -> maps.toObject(HoldsPair.class, Map.of("pair", new Object[] { "n" }))),
				refused(shorter, () -> maps.toObject(ListOfPairs.class,
						Map.of("pairs", new Object[] { new Object[] { "n" } }))));
	}

	@Test
	void aNullForAPrimitiveElementIsRefused() {
		String missing = PAIR + " is missing its required element 1";
		assertAll(
				refused(missing, () -> arrays.toObject(Pair.class, new Object[] { "n", null })),
				refused(missing, () -> arrays.toObject(HoldsPair.class, new Object[] { "l", new Object[] { "n", null } })),
				refused(missing, () -> maps.toObject(descriptor(Pair.class), new Object[] { "n", null })),
				refused(missing, () -> maps.toObject(HoldsPair.class, Map.of("pair", new Object[] { "n", null }))));
	}

	private DataClass descriptor(Class<?> type) throws DataBindException {
		return context.getDescriptor(type);
	}

	private static Executable refused(String message, Executable read) {
		return () -> assertEquals(message, assertThrows(DataBindException.class, read).getMessage());
	}
}
