/*
 * Copyright (c) 2020, Litterat Pty Ltd. All Rights Reserved.
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

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.SimplePojoDataOrder;

public class SimplePojoDataOrderTest {

	final static int TEST_A = -1;
	final static int TEST_X = 1;
	final static int TEST_Y = 2;
	final static int TEST_Z = 3;

	SimplePojoDataOrder test;

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();

		test = new SimplePojoDataOrder();
		test.setA(TEST_A);
		test.setX(TEST_X);
		test.setY(TEST_Y);
		test.setZ(TEST_Z);
	}

	@Test
	public void checkDescriptor() throws Throwable {
		DataBindContext context = new DataBindContext.Builder().build();
		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(SimplePojoDataOrder.class);
		Assertions.assertNotNull(descriptor);

		Assertions.assertEquals(SimplePojoDataOrder.class, descriptor.typeClass());
		//Assertions.assertEquals(SimplePojoDataOrder.class, descriptor.dataClass());

		DataClassField[] fields = descriptor.fields();
		Assertions.assertNotNull(fields);
		Assertions.assertEquals(4, fields.length);

		DataClassField fieldX = fields[0];
		Assertions.assertEquals("x", fieldX.name());
		Assertions.assertEquals(int.class, fieldX.type());
		Assertions.assertEquals(true, fieldX.isRequired());

		DataClassField fieldZ = fields[1];
		Assertions.assertEquals("z", fieldZ.name());
		Assertions.assertEquals(int.class, fieldZ.type());
		Assertions.assertEquals(true, fieldZ.isRequired());

		DataClassField fieldY = fields[2];
		Assertions.assertEquals("y", fieldY.name());
		Assertions.assertEquals(int.class, fieldY.type());
		Assertions.assertEquals(true, fieldY.isRequired());

		DataClassField fieldA = fields[3];
		Assertions.assertEquals("a", fieldA.name());
		Assertions.assertEquals(int.class, fieldA.type());
		Assertions.assertEquals(true, fieldA.isRequired());

	}

	@Test
	public void testToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);
		Object[] values = arrayMap.toArray(test);
		Assertions.assertNotNull(values);

		// rebuild as an object.
		SimplePojoDataOrder object = arrayMap.toObject(SimplePojoDataOrder.class, values);

		// Validate
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof SimplePojoDataOrder);
		Assertions.assertEquals(TEST_A, object.getA());
		Assertions.assertEquals(TEST_X, object.getX());
		Assertions.assertEquals(TEST_Y, object.getY());
		Assertions.assertEquals(TEST_Z, object.getZ());
	}

	@Test
	public void testToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		SimplePojoDataOrder object = (SimplePojoDataOrder) mapMapper.toObject(SimplePojoDataOrder.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof SimplePojoDataOrder);
		Assertions.assertEquals(TEST_A, object.getA());
		Assertions.assertEquals(TEST_X, object.getX());
		Assertions.assertEquals(TEST_Y, object.getY());
		Assertions.assertEquals(TEST_Z, object.getZ());
	}

	@Test
	public void testMapToObjectException() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		// corrupting the map by putting an invalid value for x.
		map.put("x", "error");

		Assertions.assertThrows(DataBindException.class, () -> {
			mapMapper.toObject(SimplePojoDataOrder.class, map);
		});

	}

}
