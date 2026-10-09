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
import java.util.Optional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.OptionalImmutable;

public class OptionalImmutableTest {

	OptionalImmutable test1 = new OptionalImmutable(Optional.empty());
	OptionalImmutable test2 = new OptionalImmutable(Optional.of("test"));

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void checkDescriptor() throws Throwable {

		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(OptionalImmutable.class);
		Assertions.assertNotNull(descriptor);

		Assertions.assertEquals(OptionalImmutable.class, descriptor.typeClass());
		//Assertions.assertEquals(OptionalImmutable.class, descriptor.dataClass());

		DataClassField[] fields = descriptor.fields();
		Assertions.assertNotNull(fields);
		Assertions.assertEquals(1, fields.length);

		DataClassField field = fields[0];
		Assertions.assertEquals("optionalString", field.name());
		Assertions.assertEquals(String.class, field.type());
		Assertions.assertEquals(false, field.isRequired());

	}

	@Test
	public void test1ToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);

		// write to array.
		Object[] values = arrayMap.toArray(test1);

		// convert to object.
		OptionalImmutable object = arrayMap.toObject(OptionalImmutable.class, values);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof OptionalImmutable);

		Assertions.assertEquals(test1.optionalString(), object.optionalString());

	}

	@Test
	public void test1ToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test1);

		OptionalImmutable object = (OptionalImmutable) mapMapper.toObject(OptionalImmutable.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof OptionalImmutable);

		Assertions.assertEquals(test1.optionalString(), object.optionalString());
	}

	@Test
	public void test2ToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);

		// write to array.
		Object[] values = arrayMap.toArray(test2);

		// convert to object.
		OptionalImmutable object = arrayMap.toObject(OptionalImmutable.class, values);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof OptionalImmutable);

		Assertions.assertEquals(test2.optionalString(), object.optionalString());

	}

	@Test
	public void test2ToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test2);

		OptionalImmutable object = (OptionalImmutable) mapMapper.toObject(OptionalImmutable.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof OptionalImmutable);

		Assertions.assertEquals(test2.optionalString(), object.optionalString());
	}

}
