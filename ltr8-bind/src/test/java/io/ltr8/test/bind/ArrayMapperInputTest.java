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
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.test.data.MapComponents;
import io.ltr8.test.data.MapComponents.HoldsMapRecords;
import io.ltr8.test.data.MapComponents.RecordKeys;
import io.ltr8.test.data.MapComponents.RecordValues;
import io.ltr8.test.data.MapperShapes.HoldsPair;
import io.ltr8.test.data.MapperShapes.HoldsTriple;
import io.ltr8.test.data.MapperShapes.ListOfRecords;
import io.ltr8.test.data.MapperShapes.ListOfUnion;
import io.ltr8.test.data.MapperShapes.Pair;
import io.ltr8.test.data.MapperShapes.Point;
import io.ltr8.test.data.MapperShapes.Triple;
import io.ltr8.test.data.union.SealedInterfaceUnionCircle;
import io.ltr8.test.data.union.SealedInterfaceUnionPoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** An {@link ArrayMapper} read leaves the array it is given as it was, at every depth. */
class ArrayMapperInputTest {

	private final ArrayMapper arrays = new ArrayMapper(DataBindContext.builder().build());

	private static final List<Object> VALUES = List.of(
			new HoldsTriple("n", new Triple(1, "b", 3L)),
			new ListOfRecords(List.of(new Point(1, 2), new Point(3, 4))),
			new RecordValues(Map.of("a", new MapComponents.Point(1, 2))),
			new RecordKeys(Map.of(new MapComponents.Point(1, 2), "a")),
			new HoldsMapRecords(List.of(new RecordValues(Map.of("a", new MapComponents.Point(1, 2))))),
			new ListOfUnion(List.of(new SealedInterfaceUnionPoint(1, 2), new SealedInterfaceUnionCircle(3, 4, 5))),
			new HoldsPair("l", new Pair("n", 2)));

	@Test
	void aReadLeavesTheArrayItIsGivenUnchanged() {
		assertAll(VALUES.stream().<Executable>map(value -> () -> {
			Object[] written = arrays.toArray(value);
			Object[] before = deepCopy(written);
			assertEquals(value, arrays.toObject(value.getClass(), written), "read " + value);
			assertArrayEquals(before, written, "input " + value);
		}));
	}

	@Test
	void aReadOfTheSameArrayTwiceGivesTheSameValue() {
		assertAll(VALUES.stream().<Executable>map(value -> () -> {
			Object[] written = arrays.toArray(value);
			arrays.toObject(value.getClass(), written);
			assertEquals(value, arrays.toObject(value.getClass(), written), "second read " + value);
		}));
	}

	private static Object[] deepCopy(Object[] values) {
		return Stream.of(values).map(v -> v instanceof Object[] nested ? deepCopy(nested) : v).toArray();
	}
}
