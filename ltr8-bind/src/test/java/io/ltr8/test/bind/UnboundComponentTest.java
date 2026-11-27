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
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.UnboundComponents.UnboundBeforeBound;
import io.ltr8.test.data.UnboundComponents.UnboundBoolean;
import io.ltr8.test.data.UnboundComponents.UnboundByte;
import io.ltr8.test.data.UnboundComponents.UnboundCanonicalParameters;
import io.ltr8.test.data.UnboundComponents.UnboundChar;
import io.ltr8.test.data.UnboundComponents.UnboundConstructorAccessors;
import io.ltr8.test.data.UnboundComponents.UnboundConstructorFields;
import io.ltr8.test.data.UnboundComponents.UnboundConstructorParameters;
import io.ltr8.test.data.UnboundComponents.UnboundConstructorSetters;
import io.ltr8.test.data.UnboundComponents.UnboundDouble;
import io.ltr8.test.data.UnboundComponents.UnboundFloat;
import io.ltr8.test.data.UnboundComponents.UnboundGetters;
import io.ltr8.test.data.UnboundComponents.UnboundInt;
import io.ltr8.test.data.UnboundComponents.UnboundLong;
import io.ltr8.test.data.UnboundComponents.UnboundOptional;
import io.ltr8.test.data.UnboundComponents.UnboundOptionalInt;
import io.ltr8.test.data.UnboundComponents.UnboundPairFields;
import io.ltr8.test.data.UnboundComponents.UnboundProfileComponents;
import io.ltr8.test.data.UnboundComponents.UnboundProfileParameters;
import io.ltr8.test.data.UnboundComponents.UnboundPublicFields;
import io.ltr8.test.data.UnboundComponents.UnboundSettableInitialised;
import io.ltr8.test.data.UnboundComponents.UnboundSetters;
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

	// --- marked on a member other than a record header's component, each shape marking an int and a String

	private static final DataBindContext PROFILED = DataBindContext.builder().profile("p").build();

	/**
	 * A shape's context, the value a read fills from {@code name = "n"} alone, and the same with its unbound
	 * components set.
	 */
	private record Shape(DataBindContext context, Object read, Object set) {
	}

	private static final List<Shape> SHAPES = List.of(
			new Shape(DataBindContext.builder().build(), new UnboundCanonicalParameters("n", 0, null),
					new UnboundCanonicalParameters("n", 7, "x")),
			new Shape(PROFILED, new UnboundProfileParameters("n", 0, null, "t"),
					new UnboundProfileParameters("n", 7, "x", "t")),
			new Shape(PROFILED, new UnboundProfileComponents("n", 0, null, "t"),
					new UnboundProfileComponents("n", 7, "x", "t")),
			new Shape(DataBindContext.builder().build(), new UnboundConstructorParameters("n", 0, null),
					new UnboundConstructorParameters("n", 7, "x")),
			new Shape(DataBindContext.builder().build(), new UnboundConstructorFields("n", 0, null),
					new UnboundConstructorFields("n", 7, "x")),
			new Shape(DataBindContext.builder().build(), new UnboundConstructorAccessors("n", 0, null),
					new UnboundConstructorAccessors("n", 7, "x")),
			new Shape(DataBindContext.builder().build(), new UnboundConstructorSetters("n", 0, null),
					new UnboundConstructorSetters("n", 7, "x")),
			new Shape(DataBindContext.builder().build(), new UnboundPublicFields("n", 0, null),
					new UnboundPublicFields("n", 7, "x")),
			new Shape(DataBindContext.builder().build(), new UnboundGetters("n", 0, null),
					new UnboundGetters("n", 7, "x")),
			new Shape(DataBindContext.builder().build(), new UnboundSetters("n", 0, null),
					new UnboundSetters("n", 7, "x")),
			new Shape(DataBindContext.builder().build(), new UnboundPairFields("n", 0, null),
					new UnboundPairFields("n", 7, "x")));

	@Test
	void anUnboundComponentOfEveryShapeIsNoFieldOfTheWire() {
		assertAll(SHAPES.stream().map(shape -> () -> {
			DataClassRecord record = assertInstanceOf(DataClassRecord.class,
					shape.context().getDescriptor(shape.read().getClass()));
			assertEquals(List.of("name"), Stream.of(record.fields()).map(DataClassField::name).toList(),
					shape.read().getClass().getName());
		}));
	}

	@Test
	void aReadThroughEitherMapperGivesAnUnboundComponentOfEveryShapeItsTypesZero() {
		assertAll(SHAPES.stream().flatMap(shape -> {
			Object read = shape.read();
			Class<?> type = read.getClass();
			return Stream.of(
					() -> assertEquals(read, new MapMapper(shape.context()).toObject(type, Map.of("name", "n")),
							"map " + type.getName()),
					() -> assertEquals(read, new ArrayMapper(shape.context()).toObject(type, new Object[] { "n" }),
							"array " + type.getName()));
		}));
	}

	@Test
	void aWriteThroughEitherMapperLeavesAnUnboundComponentOfEveryShapeOut() {
		assertAll(SHAPES.stream().flatMap(shape -> {
			Object set = shape.set();
			return Stream.of(
					() -> assertEquals(Map.of("name", "n"), new MapMapper(shape.context()).toMap(set),
							"map " + set.getClass().getName()),
					() -> assertArrayEquals(new Object[] { "n" }, new ArrayMapper(shape.context()).toArray(set),
							"array " + set.getClass().getName()));
		}));
	}

	/** The profile's constructor takes a subset; a context with no profile binds the canonical one, unmarked. */
	@Test
	void aProfileParameterMarksItsComponentForThatProfileAlone() throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class,
				context.getDescriptor(UnboundProfileParameters.class));
		assertEquals(List.of("name", "count", "note", "tier"),
				Stream.of(record.fields()).map(DataClassField::name).toList());
	}

	/** A read calls no setter of an unbound component, so the value the class gave it stands. */
	@Test
	void aReadThroughEitherMapperLeavesAnUnboundSettableComponentAsTheClassMadeIt() {
		UnboundSettableInitialised read = new UnboundSettableInitialised("n", 5, "kept");
		assertAll(
				() -> assertEquals(read, maps.toObject(UnboundSettableInitialised.class, Map.of("name", "n"))),
				() -> assertEquals(read, arrays.toObject(UnboundSettableInitialised.class, new Object[] { "n" })),
				() -> assertEquals(Map.of("name", "n"), maps.toMap(new UnboundSettableInitialised("n", 7, "x"))),
				() -> assertArrayEquals(new Object[] { "n" },
						arrays.toArray(new UnboundSettableInitialised("n", 7, "x"))));
	}
}
