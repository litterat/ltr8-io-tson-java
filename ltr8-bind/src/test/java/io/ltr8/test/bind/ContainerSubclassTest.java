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
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A map or a collection subclass binds at the type arguments its supertype is given: fixed by the subclass, or passed
 * on from its own parameters and supplied where it is described.
 */
class ContainerSubclassTest {

	public static class Names extends ArrayList<String> {
		private static final long serialVersionUID = 1L;
	}

	public static class Counts extends HashMap<String, Integer> {
		private static final long serialVersionUID = 1L;
	}

	/** Its own parameter is the map's value, not its key. */
	public static class Labels<V> extends HashMap<String, V> {
		private static final long serialVersionUID = 1L;
	}

	public static class Open<E> extends ArrayList<E> {
		private static final long serialVersionUID = 1L;
	}

	public record Holder(Names names, Counts counts, Labels<Integer> labels) {
	}

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void aSubclassFixingItsSupertypesArgumentsBindsAtThem() throws DataBindException {
		DataClassArray names = assertInstanceOf(DataClassArray.class, context.getDescriptor(Names.class));
		assertEquals(String.class, names.arrayDataClass().typeClass());
		DataClassMap counts = assertInstanceOf(DataClassMap.class, context.getDescriptor(Counts.class));
		assertEquals(String.class, counts.keyDataClass().typeClass());
		assertEquals(Integer.class, counts.valueDataClass().typeClass());
	}

	@Test
	void aSubclassPassingItsParameterOnBindsItWhereTheSupertypeTakesIt() throws DataBindException {
		DataClassMap labels = assertInstanceOf(DataClassMap.class, context.getDescriptor(Labels.class,
				new DataParameterizedType(Labels.class, Integer.class)));
		assertEquals(String.class, labels.keyDataClass().typeClass());
		assertEquals(Integer.class, labels.valueDataClass().typeClass());
	}

	@Test
	void aSubclassLeavingItsParameterOpenStillNeedsOne() {
		assertEquals("Collection must provide parameterized type information",
				assertThrows(DataBindException.class, () -> context.getDescriptor(Open.class)).getMessage());
		assertEquals("Map must provide parameterized type information",
				assertThrows(DataBindException.class, () -> context.getDescriptor(Labels.class)).getMessage());
	}

	@Test
	void eachRoundTripsThroughBothMappers() throws Throwable {
		Names names = new Names();
		names.addAll(List.of("a", "b"));
		Counts counts = new Counts();
		counts.put("x", 1);
		Labels<Integer> labels = new Labels<>();
		labels.put("y", 2);
		Holder holder = new Holder(names, counts, labels);

		MapMapper maps = new MapMapper(context);
		Map<String, Object> written = maps.toMap(holder);
		assertAll(
				() -> assertEquals(List.of("a", "b"), Arrays.asList((Object[]) written.get("names"))),
				() -> assertEquals(Map.of("x", 1), written.get("counts")),
				() -> assertEquals(Map.of("y", 2), written.get("labels")));
		Holder read = assertInstanceOf(Holder.class, maps.toObject(Holder.class, written));
		assertAll(
				() -> assertEquals(holder, read),
				() -> assertInstanceOf(Names.class, read.names()),
				() -> assertInstanceOf(Counts.class, read.counts()),
				() -> assertInstanceOf(Labels.class, read.labels()));

		ArrayMapper arrays = new ArrayMapper(context);
		Object[] array = arrays.toArray(holder);
		Holder back = arrays.toObject(Holder.class, array);
		assertAll(
				() -> assertEquals(holder, back),
				() -> assertInstanceOf(Names.class, back.names()),
				() -> assertInstanceOf(Counts.class, back.counts()),
				() -> assertInstanceOf(Labels.class, back.labels()),
				() -> assertEquals(Arrays.deepToString(array), Arrays.deepToString(arrays.toArray(back))));
	}
}
