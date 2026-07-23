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
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.bridge.PatternStringBridge;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.SimplePatternImmutable;

public class PatternStringBridgeTest {

	// Pattern has no equals()/hashCode() override -- compare pattern() text, not instances.
	final static Pattern FIRST_PATTERN = Pattern.compile("[a-z]+");
	final static Pattern SECOND_PATTERN = Pattern.compile("[0-9]+");

	SimplePatternImmutable test = new SimplePatternImmutable(FIRST_PATTERN, SECOND_PATTERN);

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void checkDescriptor() throws Throwable {
		context.registerAtom(Pattern.class, new PatternStringBridge());
	}

	@Test
	public void testToArray() throws Throwable {

		context.registerAtom(Pattern.class, new PatternStringBridge());

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);
		Object[] values = arrayMap.toArray(test);
		Assertions.assertNotNull(values);

		// rebuild as an object.
		SimplePatternImmutable object = arrayMap.toObject(SimplePatternImmutable.class, values);

		// Validate
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof SimplePatternImmutable);
		Assertions.assertEquals(FIRST_PATTERN.pattern(), object.first().pattern());
		Assertions.assertEquals(SECOND_PATTERN.pattern(), object.second().pattern());

	}

	@Test
	public void testToMap() throws Throwable {

		context.registerAtom(Pattern.class, new PatternStringBridge());

		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		SimplePatternImmutable object = (SimplePatternImmutable) mapMapper.toObject(SimplePatternImmutable.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof SimplePatternImmutable);
		Assertions.assertEquals(FIRST_PATTERN.pattern(), object.first().pattern());
		Assertions.assertEquals(SECOND_PATTERN.pattern(), object.second().pattern());
	}
}
