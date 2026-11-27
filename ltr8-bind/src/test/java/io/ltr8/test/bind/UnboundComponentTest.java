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
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.UnboundComponents.UnboundBeforeBound;
import io.ltr8.test.data.UnboundComponents.UnboundBoolean;
import io.ltr8.test.data.UnboundComponents.UnboundByte;
import io.ltr8.test.data.UnboundComponents.UnboundChar;
import io.ltr8.test.data.UnboundComponents.UnboundDouble;
import io.ltr8.test.data.UnboundComponents.UnboundFloat;
import io.ltr8.test.data.UnboundComponents.UnboundInt;
import io.ltr8.test.data.UnboundComponents.UnboundLong;
import io.ltr8.test.data.UnboundComponents.UnboundOptional;
import io.ltr8.test.data.UnboundComponents.UnboundOptionalInt;
import io.ltr8.test.data.UnboundComponents.UnboundShort;
import io.ltr8.test.data.UnboundComponents.UnboundText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A component marked {@code @Unbound} is not on the wire: no document fills it and nothing writes it, and the
 * constructor receives its type's zero.
 */
class UnboundComponentTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	/** Each record as a read fills it from {@code name = "n"} alone, and the same with its unbound component set. */
	private static final List<Object[]> READ_AND_SET = List.of(
			new Object[] { new UnboundBoolean("n", false), new UnboundBoolean("n", true) },
			new Object[] { new UnboundByte("n", (byte) 0), new UnboundByte("n", (byte) 7) },
			new Object[] { new UnboundChar("n", '\u0000'), new UnboundChar("n", 'c') },
			new Object[] { new UnboundShort("n", (short) 0), new UnboundShort("n", (short) 7) },
			new Object[] { new UnboundInt("n", 0), new UnboundInt("n", 7) },
			new Object[] { new UnboundLong("n", 0L), new UnboundLong("n", 7L) },
			new Object[] { new UnboundFloat("n", 0f), new UnboundFloat("n", 0.5f) },
			new Object[] { new UnboundDouble("n", 0d), new UnboundDouble("n", 0.5d) },
			new Object[] { new UnboundText("n", null), new UnboundText("n", "cached") },
			new Object[] { new UnboundOptional("n", Optional.empty()), new UnboundOptional("n", Optional.of("c")) },
			new Object[] { new UnboundOptionalInt("n", OptionalInt.empty()), new UnboundOptionalInt("n",
					OptionalInt.of(7)) });

	@Test
	void anUnboundComponentIsNoFieldOfTheWire() {
		assertAll(READ_AND_SET.stream().map(pair -> () -> {
			DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(pair[0].getClass()));
			assertEquals(List.of("name"), Stream.of(record.fields()).map(DataClassField::name).toList());
		}));
	}

	@Test
	void aReadThroughEitherMapperGivesAnUnboundComponentItsTypesZero() {
		assertAll(READ_AND_SET.stream().flatMap(pair -> {
			Object read = pair[0];
			Class<?> type = read.getClass();
			return Stream.of(
					() -> assertEquals(read, maps.toObject(type, Map.of("name", "n")), "map " + type.getName()),
					() -> assertEquals(read, arrays.toObject(type, new Object[] { "n" }), "array " + type.getName()));
		}));
	}

	@Test
	void aWriteThroughEitherMapperLeavesAnUnboundComponentOut() {
		assertAll(READ_AND_SET.stream().flatMap(pair -> {
			Object set = pair[1];
			return Stream.of(
					() -> assertEquals(Map.of("name", "n"), maps.toMap(set), "map " + set.getClass().getName()),
					() -> assertArrayEquals(new Object[] { "n" }, arrays.toArray(set),
							"array " + set.getClass().getName()));
		}));
	}

	@Test
	void aBoundComponentAfterAnUnboundPrimitiveRoundTripsThroughEitherMapper() throws Throwable {
		UnboundBeforeBound read = new UnboundBeforeBound(0L, 3);
		UnboundBeforeBound set = new UnboundBeforeBound(9L, 3);
		assertAll(
				() -> assertEquals(read, maps.toObject(UnboundBeforeBound.class, maps.toMap(set))),
				() -> assertEquals(read, arrays.toObject(UnboundBeforeBound.class, arrays.toArray(set))));
	}
}
