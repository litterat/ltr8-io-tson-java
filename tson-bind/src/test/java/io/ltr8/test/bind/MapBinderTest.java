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
package io.ltr8.test.bind;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;

/**
 * Exercises {@link DataClassMap}'s full MethodHandle interface directly -- not just the
 * construct-then-put half {@code TsonMapper} actually uses today, but also the
 * iterator/next/key/value half built for symmetry with {@link io.ltr8.bind.DataClassArray} and
 * a future write direction, which nothing else in this codebase calls yet.
 */
public class MapBinderTest {

	public record MapHolder(Map<String, Integer> values) {
	}

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	private DataClassMap resolveValuesField() throws Throwable {
		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(MapHolder.class);
		DataClassField[] fields = descriptor.fields();
		Assertions.assertEquals(1, fields.length);
		Assertions.assertEquals("values", fields[0].name());
		return (DataClassMap) fields[0].dataClass();
	}

	@Test
	public void resolvesKeyAndValueDataClasses() throws Throwable {
		DataClassMap mapClass = resolveValuesField();

		Assertions.assertEquals(String.class, mapClass.keyDataClass().typeClass());
		Assertions.assertEquals(Integer.class, mapClass.valueDataClass().typeClass());
	}

	@Test
	public void constructAndPut() throws Throwable {
		DataClassMap mapClass = resolveValuesField();

		Object mapData = mapClass.constructor().invoke(2);
		mapClass.put().invoke(mapData, "a", 1);
		mapClass.put().invoke(mapData, "b", 2);

		@SuppressWarnings("unchecked")
		Map<String, Integer> map = (Map<String, Integer>) mapData;
		Assertions.assertEquals(2, (int) mapClass.size().invoke(mapData));
		Assertions.assertEquals(1, map.get("a"));
		Assertions.assertEquals(2, map.get("b"));
	}

	@Test
	public void iteratorNextKeyValueRoundTrip() throws Throwable {
		DataClassMap mapClass = resolveValuesField();

		Object mapData = mapClass.constructor().invoke(2);
		mapClass.put().invoke(mapData, "a", 1);
		mapClass.put().invoke(mapData, "b", 2);

		Object iterator = mapClass.iterator().invoke(mapData);
		Map<Object, Object> collected = new HashMap<>();
		Object entry;
		while ((entry = mapClass.next().invoke(iterator)) != null) {
			Object key = mapClass.key().invoke(entry);
			Object value = mapClass.value().invoke(entry);
			collected.put(key, value);
		}

		Assertions.assertEquals(Map.of("a", 1, "b", 2), collected);
	}

	@Test
	public void nextReturnsNullOnceExhausted() throws Throwable {
		DataClassMap mapClass = resolveValuesField();

		Object mapData = mapClass.constructor().invoke(0);
		Object iterator = mapClass.iterator().invoke(mapData);

		Assertions.assertNull(mapClass.next().invoke(iterator));
	}

	// ── Non-String keys ──────────────────────────────────────────────────
	// A map key isn't special-cased to String anywhere in DataClassMap/DefaultMapBinder -- it's
	// resolved as an ordinary DataClass the same way a value is, so any registered key type works.

	public record IntKeyedMapHolder(Map<Integer, String> byId) {
	}

	@Test
	public void integerKeyedMap() throws Throwable {
		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(IntKeyedMapHolder.class);
		DataClassMap mapClass = (DataClassMap) descriptor.fields()[0].dataClass();

		Assertions.assertEquals(Integer.class, mapClass.keyDataClass().typeClass());

		Object mapData = mapClass.constructor().invoke(2);
		mapClass.put().invoke(mapData, 1, "Alice");
		mapClass.put().invoke(mapData, 2, "Bob");

		@SuppressWarnings("unchecked")
		Map<Integer, String> map = (Map<Integer, String>) mapData;
		Assertions.assertEquals("Alice", map.get(1));
		Assertions.assertEquals("Bob", map.get(2));
	}

	public record UuidKeyedMapHolder(Map<UUID, String> owners) {
	}

	@Test
	public void uuidKeyedMap() throws Throwable {
		// UUID isn't registered by a bare DataBindContext (that's a TsonMapper-specific default,
		// deliberately kept out of tson-bind itself) -- register it here the same way
		// SimpleArrayTest does for its own UUID-keyed case.
		context.registerAtom(UUID.class);

		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(UuidKeyedMapHolder.class);
		DataClassMap mapClass = (DataClassMap) descriptor.fields()[0].dataClass();

		Assertions.assertEquals(UUID.class, mapClass.keyDataClass().typeClass());

		UUID id = UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09");
		Object mapData = mapClass.constructor().invoke(1);
		mapClass.put().invoke(mapData, id, "Alice");

		@SuppressWarnings("unchecked")
		Map<UUID, String> map = (Map<UUID, String>) mapData;
		Assertions.assertEquals("Alice", map.get(id));
	}
}
