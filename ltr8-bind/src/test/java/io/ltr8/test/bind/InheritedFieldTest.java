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
import io.ltr8.test.data.InheritedFields.HoldsWeighed;
import io.ltr8.test.data.InheritedFields.Weighed;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A public field declared on a super class is read and written through handles that take the described class.
 */
class InheritedFieldTest {

	private static final List<Object> VALUES = List.of(Weighed.of("ballast", 40), Weighed.of(null, 3),
			new HoldsWeighed(Weighed.of("anchor", 90)));

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void everyFieldHandleTakesTheDescribedClass() throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(Weighed.class));
		assertEquals(List.of("label", "weight"), Arrays.stream(record.fields()).map(DataClassField::name).toList());
		for (DataClassField field : record.fields()) {
			assertEquals(Weighed.class, field.accessor().type().parameterType(0), field.name() + " accessor");
			assertEquals(Weighed.class, field.isPresent().type().parameterType(0), field.name() + " isPresent");
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
	void roundTripsThroughAMap() throws DataBindException {
		MapMapper mapper = new MapMapper(context);
		for (Object value : VALUES) {
			assertEquals(value, mapper.toObject(value.getClass(), mapper.toMap(value)), value.getClass().getName());
		}
	}
}
