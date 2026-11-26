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
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.BridgedFields.Constructed;
import io.ltr8.test.data.BridgedFields.GetterSetter;
import io.ltr8.test.data.BridgedFields.PublicField;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgedFieldTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private DataClassField countField(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		assertEquals(1, record.fields().length);
		DataClassField count = record.fields()[0];
		assertEquals("count", count.name());
		assertEquals(String.class, count.type(), "the field carries the bridge's wire type");
		assertEquals(String.class, assertInstanceOf(DataClassAtom.class, count.dataClass()).typeClass());
		assertFalse(count.isRequired());
		return count;
	}

	@Test
	void aBridgedGetterAndSetterPairIsDescribedWithASetterTakingTheWireValue() throws DataBindException {
		DataClassField count = countField(GetterSetter.class);

		assertEquals(MethodType.methodType(void.class, GetterSetter.class, String.class),
				count.setter().orElseThrow().type());
		GetterSetter value = new GetterSetter();
		count.set(value, "7");
		assertEquals(7, value.getCount());
		assertEquals("7", count.get(value));
		assertTrue(count.isPresent(value));
		assertFalse(count.isPresent(new GetterSetter()));
	}

	@Test
	void aBridgedPublicFieldIsDescribedWithASetterTakingTheWireValue() throws DataBindException {
		DataClassField count = countField(PublicField.class);

		assertEquals(MethodType.methodType(void.class, PublicField.class, String.class),
				count.setter().orElseThrow().type());
		PublicField value = new PublicField();
		count.set(value, "7");
		assertEquals(7, value.count);
		assertEquals("7", count.get(value));
	}

	@Test
	void aBridgedConstructorArgumentIsDescribedWithNoSetter() throws DataBindException {
		DataClassField count = countField(Constructed.class);

		assertTrue(count.setter().isEmpty());
		assertEquals("7", count.get(new Constructed(7)));
	}

	@Test
	void aBridgedGetterAndSetterPairRoundTripsThroughBothMappers() throws Throwable {
		GetterSetter value = new GetterSetter();
		value.setCount(42);
		GetterSetter absent = new GetterSetter();

		MapMapper maps = new MapMapper(context);
		assertEquals(Map.of("count", "42"), maps.toMap(value));
		assertEquals(42, ((GetterSetter) maps.toObject(GetterSetter.class, maps.toMap(value))).getCount());
		assertEquals(Map.of(), maps.toMap(absent));
		assertNull(((GetterSetter) maps.toObject(GetterSetter.class, Map.of())).getCount());

		ArrayMapper arrays = new ArrayMapper(context);
		assertArrayEquals(new Object[] { "42" }, arrays.toArray(value));
		assertEquals(42, arrays.toObject(GetterSetter.class, arrays.toArray(value)).getCount());
		assertArrayEquals(new Object[] { null }, arrays.toArray(absent));
		assertNull(arrays.toObject(GetterSetter.class, new Object[] { null }).getCount());
	}

	@Test
	void aBridgedPublicFieldRoundTripsThroughBothMappers() throws Throwable {
		PublicField value = new PublicField();
		value.count = 42;
		PublicField absent = new PublicField();

		MapMapper maps = new MapMapper(context);
		assertEquals(Map.of("count", "42"), maps.toMap(value));
		assertEquals(42, ((PublicField) maps.toObject(PublicField.class, maps.toMap(value))).count);
		assertEquals(Map.of(), maps.toMap(absent));
		assertNull(((PublicField) maps.toObject(PublicField.class, Map.of())).count);

		ArrayMapper arrays = new ArrayMapper(context);
		assertArrayEquals(new Object[] { "42" }, arrays.toArray(value));
		assertEquals(42, arrays.toObject(PublicField.class, arrays.toArray(value)).count);
		assertArrayEquals(new Object[] { null }, arrays.toArray(absent));
		assertNull(arrays.toObject(PublicField.class, new Object[] { null }).count);
	}

	@Test
	void aBridgedConstructorArgumentRoundTripsThroughBothMappers() throws Throwable {
		Constructed value = new Constructed(42);
		Constructed absent = new Constructed(null);

		MapMapper maps = new MapMapper(context);
		assertEquals(Map.of("count", "42"), maps.toMap(value));
		assertEquals(42, ((Constructed) maps.toObject(Constructed.class, maps.toMap(value))).count());
		assertEquals(Map.of(), maps.toMap(absent));
		assertNull(((Constructed) maps.toObject(Constructed.class, Map.of())).count());

		ArrayMapper arrays = new ArrayMapper(context);
		assertArrayEquals(new Object[] { "42" }, arrays.toArray(value));
		assertEquals(42, arrays.toObject(Constructed.class, arrays.toArray(value)).count());
		assertArrayEquals(new Object[] { null }, arrays.toArray(absent));
		assertNull(arrays.toObject(Constructed.class, new Object[] { null }).count());
	}
}
