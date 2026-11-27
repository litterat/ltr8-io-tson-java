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
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.MapperShapes.Colour;
import io.ltr8.test.data.MapperShapes.Label;
import io.ltr8.test.data.MapperShapes.ListOfMembers;
import io.ltr8.test.data.MapperShapes.Member;
import io.ltr8.test.data.MapperShapes.Plain;
import io.ltr8.test.data.MapperShapes.Span;
import io.ltr8.test.data.MapperShapes.TextsOrCount;
import io.ltr8.test.data.union.EmbeddedUnion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A union value carries its member's class name whatever form the member is written in. {@link MapMapper} writes
 * a member written as a map of its fields as that map with the name beside them, and every other member -- one
 * written as an array, a list or an atom -- as a map of the name and its value; {@link ArrayMapper} writes every
 * member as a pair of the two.
 */
class UnionMemberFormMapperTest {

	private static final String SPAN = Span.class.getName();

	private static final String TEXTS = String[].class.getName();

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	private static final ListOfMembers MEMBERS = new ListOfMembers(Arrays.asList(new Plain(1), new Span(1, 2),
			new Label("x"), Colour.RED, null));

	private static final List<Object> VALUES = List.of(MEMBERS, new TextsOrCount(new String[] { "a", "b" }),
			new TextsOrCount(3));

	@Test
	void aMemberOfEveryFormRoundTripsThroughBothMappers() {
		assertAll(VALUES.stream().flatMap(value -> {
			Class<?> type = value.getClass();
			return Stream.of(
					() -> assertEquals(value, maps.toObject(type, maps.toMap(value)), "map " + value),
					() -> assertEquals(value, arrays.toObject(type, arrays.toArray(value)), "array " + value));
		}));
	}

	@Test
	void anAtomMemberRoundTripsThroughBothMappers() {
		assertAll(Stream.of("abc", 4).flatMap(identifier -> {
			EmbeddedUnion value = new EmbeddedUnion(identifier);
			return Stream.of(
					() -> assertEquals(identifier, ((EmbeddedUnion) maps.toObject(EmbeddedUnion.class,
							maps.toMap(value))).identifier()),
					() -> assertEquals(identifier, arrays.toObject(EmbeddedUnion.class, arrays.toArray(value))
							.identifier()));
		}));
	}

	@Test
	void aMapMapperWritesAMemberNotWrittenAsItsFieldsAsItsValueBesideTheTag() throws DataBindException {
		Object[] members = (Object[]) maps.toMap(MEMBERS).get("members");
		Map<?, ?> span = (Map<?, ?>) members[1];
		Map<?, ?> texts = (Map<?, ?>) maps.toMap(new TextsOrCount(new String[] { "a", "b" })).get("value");
		assertAll(
				() -> assertEquals(Map.of("type", Plain.class.getName(), "size", 1), members[0]),
				() -> assertEquals(Map.of("type", SPAN), Map.of("type", span.get("type"))),
				() -> assertArrayEquals(new Object[] { 1, 2 }, (Object[]) span.get("value")),
				() -> assertEquals(2, span.size()),
				() -> assertEquals(Map.of("type", Label.class.getName(), "value", "x"), members[2]),
				() -> assertEquals(Map.of("type", Colour.class.getName(), "value", "RED"), members[3]),
				() -> assertEquals(null, members[4]),
				() -> assertEquals(TEXTS, texts.get("type")),
				() -> assertArrayEquals(new Object[] { "a", "b" }, (Object[]) texts.get("value")),
				() -> assertEquals(2, texts.size()),
				() -> assertEquals(Map.of("value", Map.of("type", Integer.class.getName(), "value", 3)),
						maps.toMap(new TextsOrCount(3))),
				() -> assertEquals(Map.of("identifier", Map.of("type", String.class.getName(), "value", "abc")),
						maps.toMap(new EmbeddedUnion("abc"))));
	}

	@Test
	void anArrayMapperWritesEveryMemberAsAPairOfItsNameAndItsValue() throws Throwable {
		Object[] members = (Object[]) arrays.toArray(MEMBERS)[0];
		assertAll(
				() -> assertArrayEquals(new Object[] { Plain.class.getName(), new Object[] { 1 } },
						(Object[]) members[0]),
				() -> assertArrayEquals(new Object[] { SPAN, new Object[] { 1, 2 } }, (Object[]) members[1]),
				() -> assertArrayEquals(new Object[] { Label.class.getName(), "x" }, (Object[]) members[2]),
				() -> assertArrayEquals(new Object[] { Colour.class.getName(), "RED" }, (Object[]) members[3]),
				() -> assertArrayEquals(new Object[] { new Object[] { TEXTS, new Object[] { "a", "b" } } },
						arrays.toArray(new TextsOrCount(new String[] { "a", "b" }))));
	}

	@Test
	void aMemberNotWrittenAsItsFieldsWithoutItsValueIsRefused() {
		String suffix = " is not written as a map of its fields, so a union value of it holds its value under 'value'";
		assertAll(
				refused(SPAN + suffix, () -> maps.toObject(ListOfMembers.class,
						Map.of("members", new Object[] { Map.of("type", SPAN) }))),
				refused(SPAN + suffix, () -> maps.toObject(ListOfMembers.class,
						Map.of("members", new Object[] { Map.of("type", SPAN, "from", 1, "to", 2) }))),
				refused(TEXTS + suffix, () -> maps.toObject(TextsOrCount.class,
						Map.of("value", Map.of("type", TEXTS)))));
	}

	/** A value that is no map names no member, so its own class must be one, as an atom member's is. */
	@Test
	void aValueThatIsNoMapIsReadOnlyWhereItsClassIsAMember() {
		assertAll(
				refused("'java.lang.String' is not a member of union " + Member.class.getName(),
						() -> maps.toObject(ListOfMembers.class, Map.of("members", new Object[] { "x" }))),
				refused("'[Ljava.lang.Object;' is not a member of union " + Member.class.getName(),
						() -> maps.toObject(ListOfMembers.class,
								Map.of("members", new Object[] { new Object[] { 1, 2 } }))),
				refused("'java.lang.Long' is not a member of union java.lang.Object",
						() -> maps.toObject(EmbeddedUnion.class, Map.of("identifier", 1L))),
				() -> assertEquals(new EmbeddedUnion("abc").identifier(), ((EmbeddedUnion) maps.toObject(
						EmbeddedUnion.class, Map.of("identifier", "abc"))).identifier()),
				() -> assertEquals(new TextsOrCount(3), maps.toObject(TextsOrCount.class, Map.of("value", 3))));
	}

	private static Executable refused(String message, Executable read) {
		return () -> assertEquals(message, assertThrows(DataBindException.class, read).getMessage());
	}
}
