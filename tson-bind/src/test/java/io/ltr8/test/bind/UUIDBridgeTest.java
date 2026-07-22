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
import io.ltr8.test.data.SimpleUUIDImmutable;
import io.ltr8.test.data.UUIDBridge;

public class UUIDBridgeTest {

	final static UUID FIRST_UUID = UUID.randomUUID();
	final static UUID SECOND_UUID = UUID.randomUUID();

	SimpleUUIDImmutable test = new SimpleUUIDImmutable(FIRST_UUID, SECOND_UUID);

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void checkDescriptor() throws Throwable {
		context.registerAtom(UUID.class, new UUIDBridge());

	}

	@Test
	public void testToArray() throws Throwable {

		context.registerAtom(UUID.class, new UUIDBridge());

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);
		Object[] values = arrayMap.toArray(test);
		Assertions.assertNotNull(values);

		// rebuild as an object.
		SimpleUUIDImmutable object = arrayMap.toObject(SimpleUUIDImmutable.class, values);

		// Validate
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof SimpleUUIDImmutable);
		Assertions.assertEquals(FIRST_UUID, object.first());
		Assertions.assertEquals(SECOND_UUID, object.second());

	}

	@Test
	public void testToMap() throws Throwable {

		context.registerAtom(UUID.class, new UUIDBridge());

		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		SimpleUUIDImmutable object = (SimpleUUIDImmutable) mapMapper.toObject(SimpleUUIDImmutable.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof SimpleUUIDImmutable);
		Assertions.assertEquals(FIRST_UUID, object.first());
		Assertions.assertEquals(SECOND_UUID, object.second());
	}
}
