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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** An enum constant is written by its {@code name()}, whatever its {@code toString()} says. */
class EnumNameMapperTest {

	private static final Components COMPONENTS = new Components(Plain.ONE, Shouted.QUIET, Bodied.BODIED);

	private static final Members MEMBERS = new Members(List.of(Plain.ONE, Shouted.QUIET, Bodied.PLAIN, Bodied.BODIED));

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	@Test
	void aMapMapperWritesEachConstantByName() throws Exception {
		Object[] members = (Object[]) maps.toMap(MEMBERS).get("members");
		assertAll(
				() -> assertEquals(Map.of("plain", "ONE", "shouted", "QUIET", "bodied", "BODIED"),
						maps.toMap(COMPONENTS)),
				() -> assertEquals(Map.of("type", Plain.class.getName(), "value", "ONE"), members[0]),
				() -> assertEquals(Map.of("type", Shouted.class.getName(), "value", "QUIET"), members[1]),
				() -> assertEquals(Map.of("type", Bodied.class.getName(), "value", "PLAIN"), members[2]),
				() -> assertEquals(Map.of("type", Bodied.class.getName(), "value", "BODIED"), members[3]));
	}

	@Test
	void anArrayMapperWritesEachConstantByName() throws Throwable {
		Object[] members = (Object[]) arrays.toArray(MEMBERS)[0];
		assertAll(
				() -> assertArrayEquals(new Object[] { "ONE", "QUIET", "BODIED" }, arrays.toArray(COMPONENTS)),
				() -> assertArrayEquals(new Object[] { Plain.class.getName(), "ONE" }, (Object[]) members[0]),
				() -> assertArrayEquals(new Object[] { Shouted.class.getName(), "QUIET" }, (Object[]) members[1]),
				() -> assertArrayEquals(new Object[] { Bodied.class.getName(), "PLAIN" }, (Object[]) members[2]),
				() -> assertArrayEquals(new Object[] { Bodied.class.getName(), "BODIED" }, (Object[]) members[3]));
	}

	@Test
	void eachConstantRoundTripsThroughBothMappersAsAComponentAndAsAUnionMember() {
		assertAll(Stream.of(COMPONENTS, MEMBERS).flatMap(value -> {
			Class<?> type = value.getClass();
			return Stream.of(
					() -> assertEquals(value, maps.toObject(type, maps.toMap(value)), "map " + value),
					() -> assertEquals(value, arrays.toObject(type, arrays.toArray(value)), "array " + value));
		}));
	}

	public sealed interface Member permits Plain, Shouted, Bodied {
	}

	public enum Plain implements Member {
		ONE, TWO
	}

	public enum Shouted implements Member {
		QUIET;

		@Override
		public String toString() {
			return "quiet!";
		}
	}

	public enum Bodied implements Member {
		PLAIN,
		BODIED {
			@Override
			public String toString() {
				return "with a body";
			}
		}
	}

	public record Components(Plain plain, Shouted shouted, Bodied bodied) {
	}

	public record Members(List<Member> members) {
	}
}
