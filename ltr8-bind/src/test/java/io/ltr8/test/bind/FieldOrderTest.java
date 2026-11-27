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
import io.ltr8.test.data.FieldOrders.OrderedByJavaName;
import io.ltr8.test.data.FieldOrders.OrderedPublicFields;
import io.ltr8.test.data.FieldOrders.OrderedRecord;
import io.ltr8.test.data.FieldOrders.OrderedWithUnbound;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@code @FieldOrder} sets a record's field order. The refusals are {@link RecordRefusalTest}'s; a hand-written
 * {@code @Record} class is ordered in {@link SimpleImmutableDataOrderTest}, {@link SimplePojoDataOrderTest} and
 * {@link MixedImmutableDataOrderTest}.
 */
class FieldOrderTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private final ArrayMapper arrays = new ArrayMapper(context);

	@Test
	void aJavaRecordTakesItsOrderInFieldsAndOnTheWire() throws Throwable {
		assertEquals(List.of("b", "a"), names(OrderedRecord.class));
		OrderedRecord value = new OrderedRecord("x", 1);
		assertArrayEquals(new Object[] { 1, "x" }, arrays.toArray(value));
		assertEquals(value, arrays.toObject(OrderedRecord.class, new Object[] { 1, "x" }));
	}

	@Test
	void aClassOfPublicFieldsTakesItsOrderInFieldsAndOnTheWire() throws Throwable {
		assertEquals(List.of("b", "a"), names(OrderedPublicFields.class));
		OrderedPublicFields value = new OrderedPublicFields("x", 1);
		assertArrayEquals(new Object[] { 1, "x" }, arrays.toArray(value));
		assertEquals(value, arrays.toObject(OrderedPublicFields.class, new Object[] { 1, "x" }));
	}

	@Test
	void anOrderNamesAComponentByItsJavaNameNotItsWireName() throws DataBindException {
		assertEquals(List.of("y", "z"), names(OrderedByJavaName.class));
	}

	@Test
	void anOrderListsAnUnboundComponentThatStaysOffTheWire() throws Throwable {
		assertEquals(List.of("name"), names(OrderedWithUnbound.class));
		assertArrayEquals(new Object[] { "n" }, arrays.toArray(new OrderedWithUnbound("n", 7)));
		assertEquals(new OrderedWithUnbound("n", 0), arrays.toObject(OrderedWithUnbound.class, new Object[] { "n" }));
	}

	private List<String> names(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		return Stream.of(record.fields()).map(DataClassField::name).toList();
	}
}
