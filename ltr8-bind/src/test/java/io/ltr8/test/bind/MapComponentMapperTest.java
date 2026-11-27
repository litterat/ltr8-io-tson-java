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

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.MapComponents.AnnotatedKeys;
import io.ltr8.test.data.MapComponents.AnnotatedRecordKeys;
import io.ltr8.test.data.MapComponents.AtomValues;
import io.ltr8.test.data.MapComponents.BridgedKeys;
import io.ltr8.test.data.MapComponents.EnumKeys;
import io.ltr8.test.data.MapComponents.HoldsMapRecords;
import io.ltr8.test.data.MapComponents.ListValues;
import io.ltr8.test.data.MapComponents.MapValues;
import io.ltr8.test.data.MapComponents.OptionalMap;
import io.ltr8.test.data.MapComponents.OrderedValues;
import io.ltr8.test.data.MapComponents.Point;
import io.ltr8.test.data.MapComponents.RecordKeys;
import io.ltr8.test.data.MapComponents.RecordValues;
import io.ltr8.test.data.SimpleEnum;
import io.ltr8.test.data.UUIDBridge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A map component is written and read by both mappers: by {@link MapMapper} as a map, and by {@link ArrayMapper} as
 * an array of {@code [key, value]} entries; either way each key and value takes its own mapper's form.
 */
class MapComponentMapperTest {

	private static final UUID OWNER = UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09");

	private final DataBindContext context = DataBindContext.builder().registerAtom(UUID.class, new UUIDBridge())
			.build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	@Test
	void everyKindOfMapComponentRoundTripsThroughBothMappers() {
		Map<String, Integer> withNull = new HashMap<>();
		withNull.put("a", null);
		Map<String, Point> withNullRecord = new HashMap<>();
		withNullRecord.put("p", null);
		List<Object> values = List.of(new AtomValues(Map.of("a", 1, "b", 2)), new AtomValues(Map.of()),
				new AtomValues(withNull), new RecordValues(Map.of("p", new Point(1, 2), "q", new Point(3, 4))),
				new RecordValues(withNullRecord), new RecordKeys(Map.of(new Point(0, 0), "origin")),
				new EnumKeys(Map.of(SimpleEnum.ONE, 1, SimpleEnum.TWO, 2)), new BridgedKeys(Map.of(OWNER, "Alice")),
				new ListValues(Map.of("s", List.of(1, 2, 3), "e", List.of())),
				new MapValues(Map.of("outer", Map.of("inner", 1))), new OptionalMap(Optional.of(Map.of("a", 1))),
				new OptionalMap(Optional.of(Map.of())), new OptionalMap(Optional.empty()),
				new HoldsMapRecords(List.of(new RecordValues(Map.of("p", new Point(1, 2))))));
		assertAll(values.stream().flatMap(value -> {
			Class<?> type = value.getClass();
			return Stream.of(
					() -> assertEquals(value, maps.toObject(type, maps.toMap(value)), "map " + type.getSimpleName()),
					() -> assertEquals(value, arrays.toObject(type, arrays.toArray(value)),
							"array " + type.getSimpleName()));
		}));
	}

	@Test
	void anArrayMapperWritesAMapAsAnArrayOfKeyValueEntriesInArrayForm() throws Throwable {
		assertAll(
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { "a", 1 } } },
						arrays.toArray(new AtomValues(Map.of("a", 1)))),
				() -> assertArrayEquals(new Object[] { new Object[] {} }, arrays.toArray(new AtomValues(Map.of()))),
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { "p", new Object[] { 1, 2 } } } },
						arrays.toArray(new RecordValues(Map.of("p", new Point(1, 2))))),
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { new Object[] { 0, 0 }, "o" } } },
						arrays.toArray(new RecordKeys(Map.of(new Point(0, 0), "o")))),
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { "ONE", 1 } } },
						arrays.toArray(new EnumKeys(Map.of(SimpleEnum.ONE, 1)))),
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { OWNER.toString(), "A" } } },
						arrays.toArray(new BridgedKeys(Map.of(OWNER, "A")))),
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { "s", new Object[] { 1 } } } },
						arrays.toArray(new ListValues(Map.of("s", List.of(1))))),
				() -> assertArrayEquals(new Object[] { null }, arrays.toArray(new OptionalMap(Optional.empty()))));
	}

	@Test
	void aMapMapperWritesAMapAsAMapOfKeysAndValuesInMapForm() throws Throwable {
		assertAll(
				() -> assertEquals(Map.of("counts", Map.of("a", 1)), maps.toMap(new AtomValues(Map.of("a", 1)))),
				() -> assertEquals(Map.of("counts", Map.of()), maps.toMap(new AtomValues(Map.of()))),
				() -> assertEquals(Map.of("points", Map.of("p", Map.of("x", 1, "y", 2))),
						maps.toMap(new RecordValues(Map.of("p", new Point(1, 2))))),
				() -> assertEquals(Map.of("labels", Map.of(Map.of("x", 0, "y", 0), "o")),
						maps.toMap(new RecordKeys(Map.of(new Point(0, 0), "o")))),
				() -> assertEquals(Map.of("ranks", Map.of("ONE", 1)), maps.toMap(new EnumKeys(Map.of(SimpleEnum.ONE, 1)))),
				() -> assertEquals(Map.of("owners", Map.of(OWNER.toString(), "A")),
						maps.toMap(new BridgedKeys(Map.of(OWNER, "A")))),
				() -> assertEquals(Map.of(), maps.toMap(new OptionalMap(Optional.empty()))));
	}

	/** Both forms keep the order the map iterates in, so an ordered map reads back in the order it was written. */
	@Test
	void anOrderedMapKeepsItsOrderThroughBothMappers() throws Throwable {
		SequencedMap<String, Integer> ranked = new LinkedHashMap<>();
		for (String key : List.of("z", "a", "m", "b", "y", "c")) {
			ranked.put(key, ranked.size());
		}
		OrderedValues value = new OrderedValues(ranked);
		Object[] entries = (Object[]) arrays.toArray(value)[0];
		List<Object> written = new ArrayList<>();
		for (Object entry : entries) {
			written.add(((Object[]) entry)[0]);
		}
		assertAll(
				() -> assertEquals(List.copyOf(ranked.keySet()), written),
				() -> assertEquals(List.copyOf(ranked.keySet()), List.copyOf(
						arrays.toObject(OrderedValues.class, arrays.toArray(value)).ranked().keySet())),
				() -> assertEquals(List.copyOf(ranked.keySet()), List.copyOf(((OrderedValues) maps
						.toObject(OrderedValues.class, maps.toMap(value))).ranked().keySet())));
	}

	/**
	 * An {@link AnnotatedMap} is written as its keys and values, as any map is, and read back into one. Neither
	 * form has a place for a key's annotations, so the key reads back without them.
	 */
	@Test
	void anAnnotatedMapCarriesItsKeysThroughBothMappers() throws Throwable {
		AnnotatedMap<String, Integer> counts = new AnnotatedMap<>();
		counts.put("z", 1, Annotations.of(List.of(Annotation.of("doc", "last"))));
		counts.put("a", 2);
		AnnotatedMap<Point, String> labels = new AnnotatedMap<>();
		labels.put(new Point(0, 0), "o");
		AnnotatedKeys keys = new AnnotatedKeys(counts);
		AnnotatedRecordKeys recordKeys = new AnnotatedRecordKeys(labels);
		assertAll(
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { "z", 1 }, new Object[] { "a", 2 } } },
						arrays.toArray(keys)),
				() -> assertArrayEquals(new Object[] { new Object[] { new Object[] { new Object[] { 0, 0 }, "o" } } },
						arrays.toArray(recordKeys)),
				() -> assertEquals(Map.of("counts", Map.of("z", 1, "a", 2)), maps.toMap(keys)),
				() -> assertEquals(Map.of("labels", Map.of(Map.of("x", 0, "y", 0), "o")), maps.toMap(recordKeys)),
				() -> assertEquals(keys, arrays.toObject(AnnotatedKeys.class, arrays.toArray(keys))),
				() -> assertEquals(keys, maps.toObject(AnnotatedKeys.class, maps.toMap(keys))),
				() -> assertEquals(recordKeys, arrays.toObject(AnnotatedRecordKeys.class, arrays.toArray(recordKeys))),
				() -> assertEquals(recordKeys, maps.toObject(AnnotatedRecordKeys.class, maps.toMap(recordKeys))),
				() -> {
					AnnotatedMap<String, Integer> read = arrays.toObject(AnnotatedKeys.class, arrays.toArray(keys))
							.counts();
					assertEquals(List.of("z", "a"), List.copyOf(read.keySet()));
					assertTrue(read.getAnnotations("z").isEmpty());
				},
				() -> {
					AnnotatedMap<String, Integer> read = ((AnnotatedKeys) maps.toObject(AnnotatedKeys.class,
							maps.toMap(keys))).counts();
					assertEquals(List.of("z", "a"), List.copyOf(read.keySet()));
					assertTrue(read.getAnnotations("z").isEmpty());
				});
	}

	@Test
	void aMapDescribedAloneRoundTripsThroughBothMappers() throws Throwable {
		Type type = RecordValues.class.getRecordComponents()[0].getGenericType();
		DataClass described = context.getDescriptor(Map.class, type);
		assertInstanceOf(DataClassMap.class, described);
		Map<String, Point> points = Map.of("p", new Point(1, 2), "q", new Point(3, 4));
		Object[] entries = arrays.toArray(described, points);
		assertAll(
				() -> assertEquals(2, entries.length),
				() -> assertEquals(points, arrays.toObject(described, entries)),
				() -> assertEquals(Map.of("p", Map.of("x", 1, "y", 2), "q", Map.of("x", 3, "y", 4)),
						maps.toMap(described, points)),
				() -> assertEquals(points, maps.toObject(described, maps.toMap(described, points))),
				() -> assertEquals(List.of(), Arrays.asList(arrays.toArray(described, Map.of()))));
	}
}
