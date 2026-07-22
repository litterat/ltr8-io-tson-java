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

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.union.SealedInterfaceUnion;
import io.ltr8.test.data.union.SealedInterfaceUnionCircle;
import io.ltr8.test.data.union.SealedInterfaceUnionList;
import io.ltr8.test.data.union.SealedInterfaceUnionPoint;
import io.ltr8.test.data.union.SealedInterfaceUnionRectangle;

public class SealedInterfaceUnionTest {

	final static SealedInterfaceUnion TEST_ONE = new SealedInterfaceUnionPoint(1, 2);
	final static SealedInterfaceUnion TEST_TWO = new SealedInterfaceUnionCircle(3, 4, 5);

	// TEST_THREE is not a valid member of sealed interface.
	final static SealedInterfaceUnion TEST_THREE = new SealedInterfaceUnionRectangle(6, 7, 8, 9);

	List<SealedInterfaceUnion> testList = List.of(TEST_ONE, TEST_TWO);
	SealedInterfaceUnion[] testArray = { TEST_ONE, TEST_TWO };

	SealedInterfaceUnionList test = new SealedInterfaceUnionList(testList, testArray);

	// TEST_THREE is not a valid member of the union.
	List<SealedInterfaceUnion> errorList = List.of(TEST_THREE);
	SealedInterfaceUnion[] errorArray = { TEST_THREE };

	SealedInterfaceUnionList errorTest = new SealedInterfaceUnionList(errorList, errorArray);

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void checkDescriptor() throws Throwable {

		DataClassUnion descriptor = (DataClassUnion) context.getDescriptor(SealedInterfaceUnion.class);
		Assertions.assertNotNull(descriptor);

		Assertions.assertEquals(SealedInterfaceUnion.class, descriptor.typeClass());

		// A union initially has no implementations.
		Class<?>[] components = descriptor.memberTypes();
		Assertions.assertNotNull(components);
		Assertions.assertEquals(2, components.length);

		Class<?> memberClass = components[0];
		DataClass memberType = context.getDescriptor(memberClass);
		Assertions.assertTrue(memberType instanceof DataClassRecord);
		Assertions.assertEquals(SealedInterfaceUnionPoint.class, memberType.typeClass());

		Class<?> memberClassCircle = components[1];
		DataClass memberTypeCircle = context.getDescriptor(memberClassCircle);
		Assertions.assertTrue(memberTypeCircle instanceof DataClassRecord);
		Assertions.assertEquals(SealedInterfaceUnionCircle.class, memberTypeCircle.typeClass());
	}

	@Test
	public void testToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);

		// write to array.
		Object[] values = arrayMap.toArray(test);

		// convert to object.
		SealedInterfaceUnionList object = arrayMap.toObject(SealedInterfaceUnionList.class, values);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof SealedInterfaceUnionList);

		Assertions.assertEquals(TEST_ONE, object.list().get(0));
		Assertions.assertEquals(TEST_TWO, object.list().get(1));

		Assertions.assertEquals(TEST_ONE, object.array()[0]);
		Assertions.assertEquals(TEST_TWO, object.array()[1]);
	}

	@Test
	public void testToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		Assertions.assertEquals(true, map.containsKey("list"));
		Object[] listArray = (Object[]) map.get("list");
		System.out.println(Arrays.toString(listArray));

		SealedInterfaceUnionList object = (SealedInterfaceUnionList) mapMapper.toObject(SealedInterfaceUnionList.class,
				map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof SealedInterfaceUnionList);

		Assertions.assertEquals(TEST_ONE, object.list().get(0));
		Assertions.assertEquals(TEST_TWO, object.list().get(1));

		Assertions.assertEquals(TEST_ONE, object.array()[0]);
		Assertions.assertEquals(TEST_TWO, object.array()[1]);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testMapToObjectException() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);

		// Fails because Rectangle is not a member of the union.
		Assertions.assertThrows(DataBindException.class, () -> {
			mapMapper.toMap(errorTest);
		});

		Map<String, Object> map = mapMapper.toMap(test);

		// Change the type of a value to a Rectangle which is not valid.
		Object[] listOfUnion = (Object[]) map.get("list");
		Map<String, Object> point = (Map<String, Object>) listOfUnion[0];
		point.put("type", "io.ltr8.test.bind.union.SealedInterfaceUnionRectangle");

		// Fails because Rectangle is not part of the SealedInterfaceUnion union list.
		Assertions.assertThrows(DataBindException.class, () -> {
			mapMapper.toObject(SealedInterfaceUnionList.class, map);
		});

	}
}
