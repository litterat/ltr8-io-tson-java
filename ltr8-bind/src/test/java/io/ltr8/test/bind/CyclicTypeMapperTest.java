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

import io.ltr8.annotation.Tuple;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A type whose graph cycles back to itself round-trips through both mappers, whichever edge closes the cycle and
 * whichever class on it is written first.
 */
class CyclicTypeMapperTest {

	public record Listed(String name, List<Listed> children) {
	}

	public record Keyed(String name, Map<String, Keyed> children) {
	}

	public record Linked(String name, Optional<Linked> next) {
	}

	public record Direct(String name, Direct next) {
	}

	@Tuple
	public record Chain(String head, Chain tail) {
	}

	public sealed interface Shape permits Leaf, Group {
	}

	public record Leaf(String name) implements Shape {
	}

	public record Group(Shape first, List<Shape> rest) implements Shape {
	}

	public record Parent(String name, Optional<Child> child) {
	}

	public record Child(String name, List<Parent> parents) {
	}

	/** Reaches a cyclic class without being on its cycle, so the class that cycles is not the one written first. */
	public record HoldsListed(Listed root) {
	}

	private static final Listed LISTED = new Listed("a", List.of(new Listed("b", List.of()),
			new Listed("c", List.of(new Listed("d", List.of())))));

	private static final List<Object> VALUES = List.of(
			LISTED,
			new Keyed("a", Map.of("b", new Keyed("b", Map.of()), "c", new Keyed("c", Map.of("d",
					new Keyed("d", Map.of()))))),
			new Linked("a", Optional.of(new Linked("b", Optional.of(new Linked("c", Optional.empty()))))),
			new Direct("a", new Direct("b", new Direct("c", null))),
			new Chain("a", new Chain("b", new Chain("c", null))),
			new Group(new Leaf("a"), List.of(new Group(new Leaf("b"), List.of()), new Leaf("c"))),
			new Parent("p", Optional.of(new Child("c", List.of(new Parent("q", Optional.empty()))))),
			new Child("c", List.of(new Parent("p", Optional.of(new Child("d", List.of()))))),
			new HoldsListed(LISTED));

	@Test
	void aCyclicTypeRoundTripsThroughBothMappers() {
		DataBindContext context = DataBindContext.builder().build();
		MapMapper maps = new MapMapper(context);
		ArrayMapper arrays = new ArrayMapper(context);
		assertAll(VALUES.stream().flatMap(value -> {
			Class<?> type = value.getClass();
			return Stream.of(
					() -> assertEquals(value, maps.toObject(context.getDescriptor(type),
							maps.toMap(context.getDescriptor(type), value)), "map " + type.getSimpleName()),
					() -> assertEquals(value, arrays.toObject(type, arrays.toArray(value)),
							"array " + type.getSimpleName()));
		}));
	}

	@Test
	void anArrayMapperWritesACyclicTypeInTheFormOfAnyOther() throws Throwable {
		ArrayMapper arrays = new ArrayMapper(DataBindContext.builder().build());
		Object[] none = new Object[0];
		assertAll(
				() -> assertArrayEquals(new Object[] { "a", new Object[] { new Object[] { "b", none },
						new Object[] { "c", new Object[] { new Object[] { "d", none } } } } }, arrays.toArray(LISTED)),
				() -> assertArrayEquals(new Object[] { "a", new Object[] { "b", null } },
						arrays.toArray(new Direct("a", new Direct("b", null)))),
				() -> assertArrayEquals(new Object[] { "a", new Object[] { "b", null } },
						arrays.toArray(new Chain("a", new Chain("b", null)))),
				() -> assertArrayEquals(new Object[] { new Object[] { Leaf.class.getName(), new Object[] { "a" } },
						none }, arrays.toArray(new Group(new Leaf("a"), List.of()))));
	}

	/** Threads sharing one mapper each build a cyclic type's functions at once, and each reads its value back. */
	@Test
	void aSharedArrayMapperBuildsACyclicTypeOnManyThreadsAtOnce() throws Exception {
		int threads = 8;
		ArrayMapper arrays = new ArrayMapper(DataBindContext.builder().build());
		CyclicBarrier start = new CyclicBarrier(threads);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<Object>> reads = new ArrayList<>();
			for (int x = 0; x < threads; x++) {
				Object value = VALUES.get(x % VALUES.size());
				Callable<Object> read = () -> {
					start.await();
					try {
						return arrays.toObject(value.getClass(), arrays.toArray(value));
					} catch (Throwable t) {
						throw new Exception(t);
					}
				};
				reads.add(pool.submit(read));
			}
			for (int x = 0; x < threads; x++) {
				assertEquals(VALUES.get(x % VALUES.size()), reads.get(x).get());
			}
		} finally {
			pool.shutdownNow();
		}
	}
}
