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
import io.ltr8.test.data.SuperConstructors.Counted;
import io.ltr8.test.data.SuperConstructors.HoldsCounted;
import io.ltr8.test.data.SuperConstructors.HoldsSized;
import io.ltr8.test.data.SuperConstructors.Sized;
import io.ltr8.test.data.SuperConstructors.TaggedCount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A hand-written immutable class whose constructor passes arguments, unchanged, to a public super class constructor
 * is described with the super class's fields as well as its own, and binds both ways.
 */
class SuperConstructorTest {

	private static final Counted COUNTED = new Counted("widget", 3);

	private static final Sized SIZED = new Sized("crate", 5_000_000_000L, 0.25);

	/** Its inherited field is absent, so its writer takes the branch that leaves the slot empty. */
	private static final Counted UNNAMED = new Counted(null, 7);

	private static final List<Object> VALUES = List.of(COUNTED, SIZED, UNNAMED,
			new TaggedCount(Optional.of("red"), 2), new TaggedCount(Optional.empty(), 4),
			new HoldsSized("box", SIZED), new HoldsCounted(UNNAMED, 2));

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void theSuperClassFieldsComeBeforeTheClassOwn() throws DataBindException {
		assertEquals(List.of("name:String", "count:int"), fields(Counted.class));
	}

	@Test
	void argumentsTakingTwoLocalSlotsAreMatchedToTheirFields() throws DataBindException {
		assertEquals(List.of("name:String", "size:long", "ratio:double"), fields(Sized.class));
	}

	@Test
	void eachFieldReadsTheValueItsConstructorArgumentSet() throws DataBindException {
		DataClassRecord sized = assertInstanceOf(DataClassRecord.class, context.getDescriptor(Sized.class));
		assertEquals(List.of("crate", 5_000_000_000L, 0.25),
				Arrays.stream(sized.fields()).map(field -> field.get(SIZED)).toList());
	}

	@ParameterizedTest
	@ValueSource(classes = { Counted.class, Sized.class, TaggedCount.class })
	void everyFieldHandleTakesTheDescribedClass(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		for (DataClassField field : record.fields()) {
			assertEquals(type, field.accessor().type().parameterType(0), field.name() + " accessor");
			assertEquals(type, field.isPresent().type().parameterType(0), field.name() + " isPresent");
		}
	}

	@Test
	void roundTripsThroughAnArray() throws Throwable {
		ArrayMapper mapper = new ArrayMapper(context);
		for (Object value : VALUES) {
			assertEquals(value, mapper.toObject(value.getClass(), mapper.toArray(value)), value.getClass().getName());
		}
	}

	@Test
	void anArrayHoldsTheSuperClassFieldsFirst() throws Throwable {
		ArrayMapper mapper = new ArrayMapper(context);
		assertEquals(List.of("widget", 3), Arrays.asList(mapper.toArray(COUNTED)));
		assertEquals(Arrays.asList(null, 7), Arrays.asList(mapper.toArray(UNNAMED)));
	}

	@Test
	void roundTripsThroughAMap() throws DataBindException {
		MapMapper mapper = new MapMapper(context);
		for (Object value : VALUES) {
			assertEquals(value, mapper.toObject(value.getClass(), mapper.toMap(value)), value.getClass().getName());
		}
	}

	private List<String> fields(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		return Arrays.stream(record.fields()).map(SuperConstructorTest::describe).toList();
	}

	private static String describe(DataClassField field) {
		return field.name() + ":" + field.type().getSimpleName();
	}
}
