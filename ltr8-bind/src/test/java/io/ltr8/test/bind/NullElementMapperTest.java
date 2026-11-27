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
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.MapperShapes.ArrayOfRecords;
import io.ltr8.test.data.MapperShapes.ListOfLists;
import io.ltr8.test.data.MapperShapes.ListOfMaps;
import io.ltr8.test.data.MapperShapes.ListOfRecords;
import io.ltr8.test.data.MapperShapes.ListOfText;
import io.ltr8.test.data.MapperShapes.ListOfUnion;
import io.ltr8.test.data.MapperShapes.Point;
import io.ltr8.test.data.MapperShapes.SetOfRecords;
import io.ltr8.test.data.union.SealedInterfaceUnionPoint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@code null} element of a list, an array or a set passes through both mappers as a {@code null}, as a map
 * component's {@code null} key or value does ({@link MapComponentMapperTest}).
 */
class NullElementMapperTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	private static final List<Object> VALUES = List.of(
			new ListOfRecords(Arrays.asList(new Point(1, 2), null, new Point(3, 4))),
			new ListOfRecords(Arrays.asList((Point) null)),
			new ArrayOfRecords(new Point[] { new Point(1, 2), null }),
			new ArrayOfRecords(new Point[] { null }),
			new SetOfRecords(new HashSet<>(Arrays.asList(new Point(1, 2), null))),
			new ListOfText(Arrays.asList("a", null)),
			new ListOfLists(Arrays.asList(List.of("a"), null)),
			new ListOfMaps(Arrays.asList(Map.of("a", 1), null)),
			new ListOfUnion(Arrays.asList(new SealedInterfaceUnionPoint(1, 2), null)));

	@Test
	void aNullElementRoundTripsThroughBothMappers() {
		assertAll(VALUES.stream().flatMap(value -> {
			Class<?> type = value.getClass();
			return Stream.of(
					() -> assertEquals(value, maps.toObject(type, maps.toMap(value)), "map " + value),
					() -> assertEquals(value, arrays.toObject(type, arrays.toArray(value)), "array " + value));
		}));
	}

	@Test
	void aNullElementIsWrittenAsANull() {
		ListOfRecords points = new ListOfRecords(Arrays.asList(new Point(1, 2), null));
		ArrayOfRecords array = new ArrayOfRecords(new Point[] { null, new Point(3, 4) });
		ListOfUnion shapes = new ListOfUnion(Arrays.asList((SealedInterfaceUnionPoint) null));
		assertAll(
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { 1, 2 }, null } },
						arrays.toArray(points)),
				() -> assertArrayEquals(new Object[] { new Object[] { null, new Object[] { 3, 4 } } },
						arrays.toArray(array)),
				() -> assertArrayEquals(new Object[] { new Object[] { null } }, arrays.toArray(shapes)),
				() -> assertArrayEquals(new Object[] { Map.of("x", 1, "y", 2), null },
						(Object[]) maps.toMap(points).get("points")),
				() -> assertArrayEquals(new Object[] { null, Map.of("x", 3, "y", 4) },
						(Object[]) maps.toMap(array).get("points")),
				() -> assertArrayEquals(new Object[] { null }, (Object[]) maps.toMap(shapes).get("shapes")));
	}
}
