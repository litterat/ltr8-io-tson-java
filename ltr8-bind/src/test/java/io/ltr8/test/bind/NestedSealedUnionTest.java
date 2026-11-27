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
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
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
 * Proves {@code UnionBinder} recurses into a permitted subclass that is itself sealed,
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
	public void aHierarchyWithNoNonSealedClassIsASealedUnion() throws Throwable {
		Assertions.assertTrue(((DataClassUnion) context.getDescriptor(Shape.class)).isSealed());
	}

	@Test
	public void aNonSealedClassBelowASealedBranchLeavesTheUnionOpen() throws Throwable {
		DataClassUnion union = (DataClassUnion) context.getDescriptor(DeepOpenShape.class);
		Assertions.assertFalse(union.isSealed());
		Assertions.assertEquals(Set.of(DeepPoint.class, DeepOpen.class), Set.of(union.memberTypes()));
		Assertions.assertTrue(union.isMemberType(DeepOpenCircle.class), "an implementation of the open class");
	}

	/** Neither a sealed branch nor the root is listed, so each is refused by name before it is described. */
	@Test
	public void aSealedUnionAdmitsNoClassOfItsTypeThatItDoesNotList() {
		Assertions.assertAll(Stream.of(Branch.class, Shape.class).map(unlisted -> () -> {
			String refusal = "'" + unlisted.getName() + "' is not a member of union " + Shape.class.getName();
			Object[] array = { new Object[] { new Object[] { unlisted.getName(), new Object[] { 3 } } },
					new Object[0] };
			Map<String, Object> map = Map.of("list", new Object[] { Map.of("type", unlisted.getName(), "a", 3) },
					"array", new Object[0]);
			Assertions.assertAll(
					() -> Assertions.assertEquals(refusal, Assertions.assertThrows(DataBindException.class,
							() -> new ArrayMapper(context).toObject(ShapeList.class, array)).getMessage()),
					() -> Assertions.assertEquals(refusal, Assertions.assertThrows(DataBindException.class,
							() -> new MapMapper(context).toObject(ShapeList.class, map)).getMessage()),
					() -> Assertions.assertFalse(((DataClassUnion) context.getDescriptor(Shape.class))
							.isMemberType(unlisted)));
		}));
	}

	public sealed interface DeepOpenShape permits DeepPoint, DeepBranch {
	}

	public record DeepPoint(int x) implements DeepOpenShape {
	}

	public sealed interface DeepBranch extends DeepOpenShape permits DeepOpen {
	}

	public non-sealed interface DeepOpen extends DeepBranch {
	}

	public record DeepOpenCircle(int radius) implements DeepOpen {
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
