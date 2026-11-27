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
import io.ltr8.test.data.MapperShapes.HoldsRequired;
import io.ltr8.test.data.MapperShapes.ListOfRequired;
import io.ltr8.test.data.MapperShapes.ListOfTriples;
import io.ltr8.test.data.MapperShapes.MapOfRequired;
import io.ltr8.test.data.MapperShapes.Required;
import io.ltr8.test.data.MapperShapes.Triple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A read missing a value for a required field -- a primitive, or a reference marked {@code @Field(required =
 * true)} -- is refused naming the class and the field, however deep the record sits, and so is a write of a null
 * required reference; an optional or nullable field beside it reads and writes as absent.
 */
class MissingRequiredFieldMapperTest {

	private static final String COUNT = Required.class.getName() + " is missing its required field 'count'";

	private static final String NAME = Required.class.getName() + " is missing its required field 'name'";

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	@Test
	void aPrimitiveAndAMarkedReferenceAreRequired() throws Throwable {
		DataClassField[] fields = ((DataClassRecord) context.getDescriptor(Required.class)).fields();
		assertEquals(List.of(true, true, false, false),
				Arrays.stream(fields).map(DataClassField::isRequired).toList());
	}

	@Test
	void aMapReadWithEveryRequiredFieldReadsTheOthersAsAbsent() {
		assertAll(
				() -> assertEquals(new Required(1, "n", Optional.empty(), null),
						maps.toObject(Required.class, Map.of("count", 1, "name", "n"))),
				() -> assertEquals(new Required(1, "n", Optional.of("x"), "l"),
						maps.toObject(Required.class, Map.of("count", 1, "name", "n", "note", "x", "label", "l"))));
	}

	@Test
	void aMapReadMissingARequiredFieldIsRefused() {
		Map<String, Object> nullName = new HashMap<>(Map.of("count", 1));
		nullName.put("name", null);
		assertAll(
				refused(COUNT, () -> maps.toObject(Required.class, Map.of("name", "n"))),
				refused(NAME, () -> maps.toObject(Required.class, Map.of("count", 1))),
				refused(NAME, () -> maps.toObject(Required.class, nullName)),
				refused(COUNT, () -> maps.toObject(Required.class, Map.of())),
				refused(NAME, () -> maps.toObject(HoldsRequired.class,
						Map.of("id", "i", "required", Map.of("count", 1)))),
				refused(COUNT, () -> maps.toObject(ListOfRequired.class,
						Map.of("items", new Object[] { Map.of("count", 1, "name", "n"), Map.of("name", "n") }))));
	}

	@Test
	void anArrayReadWithEveryRequiredFieldReadsTheOthersAsAbsent() throws Throwable {
		assertEquals(new Required(1, "n", Optional.empty(), null),
				arrays.toObject(Required.class, new Object[] { 1, "n", null, null }));
	}

	@Test
	void anArrayReadWithANullForARequiredFieldIsRefused() {
		assertAll(
				refused(COUNT, () -> arrays.toObject(Required.class, new Object[] { null, "n", null, null })),
				refused(NAME, () -> arrays.toObject(Required.class, new Object[] { 1, null, null, null })),
				refused(NAME, () -> arrays.toObject(HoldsRequired.class,
						new Object[] { "i", new Object[] { 1, null, "x", "l" } })),
				refused(COUNT, () -> arrays.toObject(ListOfRequired.class,
						new Object[] { new Object[] { new Object[] { null, "n", null, null } } })));
	}

	/** A value that would read back refused is refused on the way out too, in the read's words. */
	@Test
	void aWriteOfANullRequiredReferenceIsRefused() {
		Required unnamed = new Required(1, null, Optional.of("x"), "l");
		assertAll(
				refused(NAME, () -> maps.toMap(unnamed)),
				refused(NAME, () -> maps.toMap(new HoldsRequired("i", unnamed))),
				refused(NAME, () -> maps.toMap(new ListOfRequired(List.of(new Required(1, "n", Optional.empty(), null),
						unnamed)))),
				refused(NAME, () -> maps.toMap(new MapOfRequired(Map.of("k", unnamed)))),
				refused(NAME, () -> arrays.toArray(unnamed)),
				refused(NAME, () -> arrays.toArray(new HoldsRequired("i", unnamed))),
				refused(NAME, () -> arrays.toArray(new ListOfRequired(List.of(unnamed)))),
				refused(NAME, () -> arrays.toArray(new MapOfRequired(Map.of("k", unnamed)))));
	}

	/** A primitive always has a value, and an absent {@code Optional} or a null nullable field is not required. */
	@Test
	void aWriteWithEveryRequiredFieldWritesTheOthersAsAbsent() {
		Required required = new Required(1, "n", Optional.empty(), null);
		assertAll(
				() -> assertEquals(Map.of("count", 1, "name", "n"), maps.toMap(required)),
				() -> assertArrayEquals(new Object[] { 1, "n", null, null }, arrays.toArray(required)));
	}

	/** {@link ArrayMapperArityTest}'s refusal reaches the caller as it is from a record inside a list too. */
	@Test
	void anArrayReadOfAWrongLengthInsideAListIsRefusedAsItIs() {
		assertAll(refused(Triple.class.getName() + " has 3 fields but the array has length 1",
				() -> arrays.toObject(ListOfTriples.class, new Object[] { new Object[] { new Object[] { 1 } } })));
	}

	private static Executable refused(String message, Executable read) {
		return () -> assertEquals(message, assertThrows(DataBindException.class, read).getMessage());
	}
}
