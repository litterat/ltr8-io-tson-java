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
import io.ltr8.test.data.MapperShapes.NullableComponents;
import io.ltr8.test.data.MapperShapes.OptionalArray;
import io.ltr8.test.data.MapperShapes.OptionalList;
import io.ltr8.test.data.MapperShapes.OptionalRecord;
import io.ltr8.test.data.MapperShapes.Point;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An absent component -- an empty optional, or a {@code null} -- is written as nothing and read back as absent,
 * whatever its type: an {@link ArrayMapper} writes a {@code null} in its slot and a {@link MapMapper} no key.
 */
class AbsentComponentMapperTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	private static final List<Object> VALUES = List.of(
			new OptionalList(Optional.empty()), new OptionalList(Optional.of(List.of("a", "b"))),
			new OptionalList(Optional.of(List.of())),
			new OptionalArray(Optional.empty()), new OptionalArray(Optional.of(new String[] { "a", "b" })),
			new OptionalArray(Optional.of(new String[] {})),
			new OptionalRecord(Optional.empty()), new OptionalRecord(Optional.of(new Point(1, 2))),
			new NullableComponents(null, null, null),
			new NullableComponents(List.of("a"), new int[] { 1, 2 }, new Point(3, 4)));

	@Test
	void anAbsentComponentRoundTripsThroughBothMappers() {
		assertAll(VALUES.stream().flatMap(value -> {
			Class<?> type = value.getClass();
			return Stream.of(
					() -> assertEquals(value, maps.toObject(type, maps.toMap(value)), "map " + value),
					() -> assertEquals(value, arrays.toObject(type, arrays.toArray(value)), "array " + value));
		}));
	}

	@Test
	void anAbsentComponentIsWrittenAsNothing() {
		assertAll(
				() -> assertArrayEquals(new Object[] { null }, arrays.toArray(new OptionalList(Optional.empty()))),
				() -> assertArrayEquals(new Object[] { null }, arrays.toArray(new OptionalArray(Optional.empty()))),
				() -> assertArrayEquals(new Object[] { null }, arrays.toArray(new OptionalRecord(Optional.empty()))),
				() -> assertArrayEquals(new Object[] { null, null, null },
						arrays.toArray(new NullableComponents(null, null, null))),
				() -> assertEquals(Map.of(), maps.toMap(new OptionalList(Optional.empty()))),
				() -> assertEquals(Map.of(), maps.toMap(new OptionalArray(Optional.empty()))),
				() -> assertEquals(Map.of(), maps.toMap(new OptionalRecord(Optional.empty()))),
				() -> assertEquals(Map.of(), maps.toMap(new NullableComponents(null, null, null))));
	}
}
