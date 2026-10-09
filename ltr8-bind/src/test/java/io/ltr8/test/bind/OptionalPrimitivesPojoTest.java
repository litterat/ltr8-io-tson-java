/*
 * Copyright (c) 2021, Litterat Pty Ltd. All Rights Reserved.
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
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.OptionalPrimitivesPojo;

public class OptionalPrimitivesPojoTest {

	OptionalPrimitivesPojo test1 = new OptionalPrimitivesPojo();
	OptionalPrimitivesPojo test2 = new OptionalPrimitivesPojo();

	{
		test1.setOptionalInt(OptionalInt.empty());
		test1.setOptionalLong(OptionalLong.empty());
		test1.setOptionalDouble(OptionalDouble.empty());

		test2.setOptionalInt(OptionalInt.of(1));
		test2.setOptionalLong(OptionalLong.of(2l));
		test2.setOptionalDouble(OptionalDouble.of(3.0d));
	}

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void checkDescriptor() throws Throwable {

		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(OptionalPrimitivesPojo.class);
		Assertions.assertNotNull(descriptor);

		Assertions.assertEquals(OptionalPrimitivesPojo.class, descriptor.typeClass());
		//Assertions.assertEquals(OptionalPrimitivesPojo.class, descriptor.dataClass());

		DataClassField[] fields = descriptor.fields();
		Assertions.assertNotNull(fields);
		Assertions.assertEquals(3, fields.length);

		// POJO fields are in alphabetical order.

		DataClassField doubleField = fields[0];
		Assertions.assertEquals("optionalDouble", doubleField.name());
		Assertions.assertEquals(Double.class, doubleField.type());
		Assertions.assertEquals(false, doubleField.isRequired());

		DataClassField intField = fields[1];
		Assertions.assertEquals("optionalInt", intField.name());
		Assertions.assertEquals(Integer.class, intField.type());
		Assertions.assertEquals(false, intField.isRequired());

		DataClassField longField = fields[2];
		Assertions.assertEquals("optionalLong", longField.name());
		Assertions.assertEquals(Long.class, longField.type());
		Assertions.assertEquals(false, longField.isRequired());

	}

	@Test
	public void test1ToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);

		// write to array.
		Object[] values = arrayMap.toArray(test1);

		// convert to object.
		OptionalPrimitivesPojo object = arrayMap.toObject(OptionalPrimitivesPojo.class, values);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof OptionalPrimitivesPojo);

		Assertions.assertEquals(test1.getOptionalInt(), object.getOptionalInt());
		Assertions.assertEquals(test1.getOptionalLong(), object.getOptionalLong());
		Assertions.assertEquals(test1.getOptionalDouble(), object.getOptionalDouble());

	}

	@Test
	public void test1ToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test1);

		OptionalPrimitivesPojo object = (OptionalPrimitivesPojo) mapMapper.toObject(OptionalPrimitivesPojo.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof OptionalPrimitivesPojo);

		Assertions.assertEquals(test1.getOptionalInt(), object.getOptionalInt());
		Assertions.assertEquals(test1.getOptionalLong(), object.getOptionalLong());
		Assertions.assertEquals(test1.getOptionalDouble(), object.getOptionalDouble());
	}

	@Test
	public void test2ToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);

		// write to array.
		Object[] values = arrayMap.toArray(test2);

		// convert to object.
		OptionalPrimitivesPojo object = arrayMap.toObject(OptionalPrimitivesPojo.class, values);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof OptionalPrimitivesPojo);

		Assertions.assertEquals(test2.getOptionalInt(), object.getOptionalInt());
		Assertions.assertEquals(test2.getOptionalLong(), object.getOptionalLong());
		Assertions.assertEquals(test2.getOptionalDouble(), object.getOptionalDouble());
	}

	@Test
	public void test2ToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test2);

		OptionalPrimitivesPojo object = (OptionalPrimitivesPojo) mapMapper.toObject(OptionalPrimitivesPojo.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof OptionalPrimitivesPojo);

		Assertions.assertEquals(test2.getOptionalInt(), object.getOptionalInt());
		Assertions.assertEquals(test2.getOptionalLong(), object.getOptionalLong());
		Assertions.assertEquals(test2.getOptionalDouble(), object.getOptionalDouble());
	}

}
