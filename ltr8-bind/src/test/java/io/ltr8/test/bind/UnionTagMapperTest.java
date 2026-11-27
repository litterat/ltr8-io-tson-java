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
import io.ltr8.test.data.MapperShapes.ListOfLabelled;
import io.ltr8.test.data.MapperShapes.Renamed;
import io.ltr8.test.data.MapperShapes.Tagged;
import io.ltr8.test.data.MapperShapes.Untagged;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MapMapper} tags a union value with its class name under the key {@code type}; a member with a field of its
 * own by that name keeps the field, its map held under {@code value} beside the tag.
 */
class UnionTagMapperTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	private static final ListOfLabelled ITEMS = new ListOfLabelled(Arrays.asList(new Tagged("kind", 3),
			new Renamed("r"), new Untagged(4), new Tagged(null, 5), null));

	@Test
	void aMemberWithAFieldNamedTypeRoundTripsThroughBothMappers() throws Throwable {
		assertAll(
				() -> assertEquals(ITEMS, maps.toObject(ListOfLabelled.class, maps.toMap(ITEMS))),
				() -> assertEquals(ITEMS, arrays.toObject(ListOfLabelled.class, arrays.toArray(ITEMS))));
	}

	@Test
	void aMemberWithAFieldNamedTypeIsItsMapUnderValueBesideTheTag() throws Throwable {
		Object[] items = (Object[]) maps.toMap(ITEMS).get("items");
		assertArrayEquals(new Object[] {
				Map.of("type", Tagged.class.getName(), "value", Map.of("type", "kind", "size", 3)),
				Map.of("type", Renamed.class.getName(), "value", Map.of("type", "r")),
				Map.of("type", Untagged.class.getName(), "size", 4),
				Map.of("type", Tagged.class.getName(), "value", Map.of("size", 5)),
				null }, items);
	}

	/** The members' fields themselves: the tag holds the class name, and a member's own {@code type} its value. */
	@Test
	void theTagAndTheFieldEachKeepTheirOwnValue() throws Throwable {
		ListOfLabelled read = (ListOfLabelled) maps.toObject(ListOfLabelled.class,
				Map.of("items", new Object[] { Map.of("type", Tagged.class.getName(),
						"value", Map.of("type", Untagged.class.getName(), "size", 1)) }));
		assertEquals(List.of(new Tagged(Untagged.class.getName(), 1)), read.items());
	}

	@Test
	void aMemberWithAFieldNamedTypeWithoutItsMapUnderValueIsRefused() {
		DataBindException refused = assertThrows(DataBindException.class, () -> maps.toObject(ListOfLabelled.class,
				Map.of("items", new Object[] { Map.of("type", Tagged.class.getName(), "size", 3) })));
		assertEquals(Tagged.class.getName() + " has a field named 'type', the key a union value's class name takes, "
				+ "so a union value of it holds its fields in a map under 'value'", refused.getMessage());
	}
}
