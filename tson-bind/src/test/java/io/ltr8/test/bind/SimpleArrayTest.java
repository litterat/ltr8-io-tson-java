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
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.SimpleArray;
import io.ltr8.test.data.SimpleImmutable;
import io.ltr8.test.data.UUIDBridge;

public class SimpleArrayTest {

	SimpleImmutable a1 = new SimpleImmutable(1, 2);
	SimpleImmutable a2 = new SimpleImmutable(22, 212);
	SimpleImmutable a3 = new SimpleImmutable(13, 3);

	SimpleImmutable[] array = { a1, a2, a3 };

	SimpleArray test = new SimpleArray(array);

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void testToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);
		Object[] values = arrayMap.toArray(test);
		Assertions.assertNotNull(values);

		// rebuild as an object.
		// SimpleUUIDImmutable object = arrayMap.toObject(SimpleUUIDImmutable.class, values);

		// Validate
		// Assertions.assertNotNull(object);
		// Assertions.assertTrue(object instanceof SimpleUUIDImmutable);
		// Assertions.assertEquals(FIRST_UUID, test.first());
		// Assertions.assertEquals(SECOND_UUID, test.second());

	}

	@Test
	public void testToMap() throws Throwable {

		context.registerAtom(UUID.class, new UUIDBridge());

		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);
		Assertions.assertNotNull(map);

		SimpleArray object = (SimpleArray) mapMapper.toObject(SimpleArray.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof SimpleArray);
		Assertions.assertEquals(3, object.arrayImmutable().length);
	}
}
