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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.NestedSealedRecord.Branch;
import io.ltr8.test.data.NestedSealedRecord.DirectPoint;
import io.ltr8.test.data.NestedSealedRecord.LeafA;
import io.ltr8.test.data.NestedSealedRecord.LeafB;
import io.ltr8.test.data.NestedSealedRecord.Shape;
import io.ltr8.test.data.NestedSealedRecord.ShapeList;

/**
 * Proves {@code DefaultUnionBinder} recurses into a permitted subclass that is itself sealed,
 * rather than keeping it as an unusable "member" -- {@code Shape permits DirectPoint, Branch} and
 * {@code Branch permits LeafA, LeafB} must resolve to a flat three-member union
 * ({@code DirectPoint}/{@code LeafA}/{@code LeafB}), with {@code Branch} itself never appearing.
 */
public class NestedSealedUnionTest {

	final static Shape DIRECT = new DirectPoint(1, 2);
	final static Shape LEAF_A = new LeafA(3);
	final static Shape LEAF_B = new LeafB(4);

	List<Shape> testList = List.of(DIRECT, LEAF_A, LEAF_B);
	Shape[] testArray = { DIRECT, LEAF_A, LEAF_B };

	ShapeList test = new ShapeList(testList, testArray);

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void descriptorFlattensToConcreteLeavesOnly() throws Throwable {
		DataClassUnion descriptor = (DataClassUnion) context.getDescriptor(Shape.class);
		Assertions.assertNotNull(descriptor);
		Assertions.assertEquals(Shape.class, descriptor.typeClass());

		Set<Class<?>> members = Set.of(descriptor.memberTypes());
		Assertions.assertEquals(3, members.size(), "expected exactly the 3 flattened leaves");
		Assertions.assertTrue(members.contains(DirectPoint.class));
		Assertions.assertTrue(members.contains(LeafA.class));
		Assertions.assertTrue(members.contains(LeafB.class));

		// Branch is itself sealed, not directly instantiable -- it must never appear as a member.
		Assertions.assertFalse(members.contains(Branch.class));
	}

	@Test
	public void testToArray() throws Throwable {
		ArrayMapper arrayMap = new ArrayMapper(context);

		Object[] values = arrayMap.toArray(test);
		ShapeList object = arrayMap.toObject(ShapeList.class, values);

		Assertions.assertEquals(DIRECT, object.list().get(0));
		Assertions.assertEquals(LEAF_A, object.list().get(1));
		Assertions.assertEquals(LEAF_B, object.list().get(2));

		Assertions.assertEquals(DIRECT, object.array()[0]);
		Assertions.assertEquals(LEAF_A, object.array()[1]);
		Assertions.assertEquals(LEAF_B, object.array()[2]);
	}

	@Test
	public void testToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		ShapeList object = (ShapeList) mapMapper.toObject(ShapeList.class, map);

		Assertions.assertEquals(DIRECT, object.list().get(0));
		Assertions.assertEquals(LEAF_A, object.list().get(1));
		Assertions.assertEquals(LEAF_B, object.list().get(2));
	}
}
