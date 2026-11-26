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
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.SuperConstructors.Counted;
import io.ltr8.test.data.SuperConstructors.HoldsSized;
import io.ltr8.test.data.SuperConstructors.Sized;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A hand-written immutable class whose constructor passes arguments, unchanged, to a public super class constructor
 * is described with the super class's fields as well as its own, and binds both ways.
 */
class SuperConstructorTest {

	private static final Counted COUNTED = new Counted("widget", 3);

	private static final Sized SIZED = new Sized("crate", 5_000_000_000L, 0.25);

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

	@Test
	void roundTripsThroughAMap() throws DataBindException {
		MapMapper mapper = new MapMapper(context);
		assertEquals(COUNTED, mapper.toObject(Counted.class, mapper.toMap(COUNTED)));
		assertEquals(SIZED, mapper.toObject(Sized.class, mapper.toMap(SIZED)));
	}

	@Test
	void roundTripsHeldAsAFieldOfARecord() throws DataBindException {
		HoldsSized holder = new HoldsSized("box", SIZED);
		MapMapper mapper = new MapMapper(context);
		assertEquals(holder, mapper.toObject(HoldsSized.class, mapper.toMap(holder)));
	}

	private List<String> fields(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		return Arrays.stream(record.fields()).map(SuperConstructorTest::describe).toList();
	}

	private static String describe(DataClassField field) {
		return field.name() + ":" + field.type().getSimpleName();
	}
}
