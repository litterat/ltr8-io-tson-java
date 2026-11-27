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
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.MapperShapes.Pair;
import io.ltr8.test.data.MapperShapes.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MapMapper#toMap(Object)} returns a map, so it refuses a root value written in any other form, which
 * {@link MapMapper#toMap(DataClass, Object)} writes and {@link MapMapper#toObject(DataClass, Object)} reads back.
 */
class MapMapperRootTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	@Test
	void aRootWrittenInAnotherFormThanAMapIsRefused() {
		assertAll(
				refused(Pair.class.getName(), () -> maps.toMap(new Pair("n", 2))),
				refused("java.lang.String[]", () -> maps.toMap(new String[] { "a", "b" })),
				refused("java.lang.String", () -> maps.toMap("a")));
	}

	@Test
	void aRootWrittenAsAMapIsReturned() throws DataBindException {
		assertEquals(Map.of("x", 1, "y", 2), maps.toMap(new Point(1, 2)));
	}

	@Test
	void aTupleAListAndAnArrayAtTheRootRoundTripThroughTheDescribedOverloads() throws DataBindException {
		DataClass pair = context.getDescriptor(Pair.class);
		DataClass list = context.getDescriptor(List.class, new DataParameterizedType(List.class, Pair.class));
		DataClass array = context.getDescriptor(String[].class);
		Object pairs = maps.toMap(list, List.of(new Pair("a", 1), new Pair("b", 2)));
		assertAll(
				() -> assertArrayEquals(new Object[] { "n", 2 }, (Object[]) maps.toMap(pair, new Pair("n", 2))),
				() -> assertArrayEquals(new Object[] { new Object[] { "a", 1 }, new Object[] { "b", 2 } },
						(Object[]) pairs),
				() -> assertEquals(new Pair("n", 2), maps.toObject(pair, maps.toMap(pair, new Pair("n", 2)))),
				() -> assertEquals(List.of(new Pair("a", 1), new Pair("b", 2)), maps.toObject(list, pairs)),
				() -> assertArrayEquals(new String[] { "a", "b" },
						(String[]) maps.toObject(array, maps.toMap(array, new String[] { "a", "b" }))));
	}

	private static Executable refused(String type, Executable write) {
		return () -> assertEquals(type + " is not written as a map: write it with toMap(DataClass, Object), "
				+ "which returns a value in any form", assertThrows(DataBindException.class, write).getMessage());
	}
}
