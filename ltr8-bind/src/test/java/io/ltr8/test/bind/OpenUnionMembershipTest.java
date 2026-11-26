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
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.test.data.OpenUnionMembers.Circle;
import io.ltr8.test.data.OpenUnionMembers.CircleHolder;
import io.ltr8.test.data.OpenUnionMembers.Figure;
import io.ltr8.test.data.OpenUnionMembers.Line;
import io.ltr8.test.data.OpenUnionMembers.OfBrokenUnion;
import io.ltr8.test.data.OpenUnionMembers.OfRecordShapedUnion;
import io.ltr8.test.data.OpenUnionMembers.OfUndescribableUnion;
import io.ltr8.test.data.OpenUnionMembers.Point;
import io.ltr8.test.data.OpenUnionMembers.RecordShapedUnion;
import io.ltr8.test.data.OpenUnionMembers.Shape;
import io.ltr8.test.data.OpenUnionMembers.Square;
import io.ltr8.test.data.OpenUnionMembers.SquareHolder;
import io.ltr8.test.data.OpenUnionMembers.Triangle;
import io.ltr8.test.data.OpenUnionMembers.TriangleHolder;
import io.ltr8.test.data.OpenUnionMembers.Undescribable;
import io.ltr8.test.data.OpenUnionMembers.UndescribableUnion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A class is a member of an open union once it is described, and a class that is refused is not. */
class OpenUnionMembershipTest {

	private static Set<Class<?>> members(DataBindContext context, Class<?> union) throws DataBindException {
		Class<?>[] members = assertInstanceOf(DataClassUnion.class, context.getDescriptor(union)).memberTypes();
		Set<Class<?>> distinct = Set.of(members);
		assertEquals(members.length, distinct.size(), "no member is listed twice");
		return distinct;
	}

	private static void assertRefused(DataBindContext context, Class<?> type) {
		DataBindException refused = assertThrows(DataBindException.class, () -> context.getDescriptor(type));
		assertTrue(refused.getMessage().contains(Undescribable.class.getName()), refused.getMessage());
	}

	private static void assertNotAMember(DataBindContext context, Class<?> union, Class<?> refused)
			throws DataBindException {
		assertFalse(members(context, union).contains(refused), "a refused class is not listed");
		assertFalse(((DataClassUnion) context.getDescriptor(union)).isMemberType(refused));
	}

	@Test
	void noClassIsAMemberBeforeItIsDescribed() throws DataBindException {
		assertEquals(Set.of(), members(DataBindContext.builder().build(), Shape.class));
	}

	@Test
	void aDescribedClassIsAMember() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();
		context.getDescriptor(Point.class);
		assertEquals(Set.of(Point.class), members(context, Shape.class));
		context.getDescriptor(Line.class);
		assertEquals(Set.of(Point.class, Line.class), members(context, Shape.class));
	}

	@Test
	void aClassDescribedOnACycleIsAMemberOnceTheCycleIsDescribed() throws DataBindException {
		DataBindContext throughHolder = DataBindContext.builder().build();
		throughHolder.getDescriptor(SquareHolder.class);
		assertEquals(Set.of(Square.class), members(throughHolder, Shape.class));

		DataBindContext direct = DataBindContext.builder().build();
		members(direct, Shape.class);
		direct.getDescriptor(Square.class);
		assertEquals(Set.of(Square.class), members(direct, Shape.class));
	}

	@Test
	void aClassRefusedOnACycleIsNotAMemberOfAUnionCachedBefore() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();
		assertEquals(Set.of(), members(context, Shape.class));

		assertRefused(context, CircleHolder.class);
		assertNotAMember(context, Shape.class, Circle.class);
		assertRefused(context, Circle.class);
		assertNotAMember(context, Shape.class, Circle.class);
	}

	@Test
	void aClassRefusedOnACycleIsNotAMemberOfAUnionDescribedAfter() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();
		assertRefused(context, CircleHolder.class);
		assertNotAMember(context, Shape.class, Circle.class);
	}

	@Test
	void aClassRefusedOnACycleIsNotAMemberWhenItIsAskedForFirst() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();
		assertRefused(context, Circle.class);
		assertRefused(context, CircleHolder.class);
		assertNotAMember(context, Shape.class, Circle.class);
	}

	@Test
	void aClassRefusedOnACycleIsNotAMemberOfItsSuperClassUnion() throws DataBindException {
		DataBindContext cachedBefore = DataBindContext.builder().build();
		assertEquals(Set.of(), members(cachedBefore, Figure.class));
		assertRefused(cachedBefore, TriangleHolder.class);
		assertNotAMember(cachedBefore, Figure.class, Triangle.class);

		DataBindContext describedAfter = DataBindContext.builder().build();
		assertRefused(describedAfter, TriangleHolder.class);
		assertNotAMember(describedAfter, Figure.class, Triangle.class);
		assertRefused(describedAfter, Triangle.class);
		assertNotAMember(describedAfter, Figure.class, Triangle.class);
	}

	@Test
	void aRefusalLeavesTheMembersDescribedBeforeAndAfterIt() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();
		context.getDescriptor(Point.class);
		assertRefused(context, CircleHolder.class);
		context.getDescriptor(SquareHolder.class);
		assertEquals(Set.of(Point.class, Square.class), members(context, Shape.class));
	}

	@Test
	void aClassImplementingAUnionThisContextCannotDescribeIsStillDescribed() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();
		assertInstanceOf(DataClassRecord.class, context.getDescriptor(OfUndescribableUnion.class));
		assertThrows(DataBindException.class, () -> context.getDescriptor(UndescribableUnion.class));
	}

	@Test
	void aClassExtendingAUnionDescribedAsARecordIsStillDescribed() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();
		assertInstanceOf(DataClassRecord.class, context.getDescriptor(OfRecordShapedUnion.class));
		assertInstanceOf(DataClassRecord.class, context.getDescriptor(RecordShapedUnion.class));
	}

	@Test
	void anErrorDescribingAUnionIsNotSwallowed() {
		DataBindContext context = DataBindContext.builder().build();
		ExceptionInInitializerError broken = assertThrows(ExceptionInInitializerError.class,
				() -> context.getDescriptor(OfBrokenUnion.class));
		assertEquals("this bridge has no class", broken.getCause().getMessage());
		assertThrows(NoClassDefFoundError.class, () -> context.getDescriptor(OfBrokenUnion.class),
				"and the class is not cached as described");
	}

	@Test
	void threadsDescribingMembersAtOnceLeaveEachDescribedMemberListedOnce() throws Exception {
		List<Class<?>> types = List.of(Point.class, CircleHolder.class, SquareHolder.class, Circle.class,
				Line.class, TriangleHolder.class, Square.class, Triangle.class);
		int threads = 8;
		int rounds = 500;
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int round = 0; round < rounds; round++) {
				DataBindContext shared = DataBindContext.builder().build();
				CyclicBarrier start = new CyclicBarrier(threads);
				List<Future<?>> asks = new ArrayList<>();
				for (int t = 0; t < threads; t++) {
					List<Class<?>> order = new ArrayList<>(types);
					Collections.rotate(order, t);
					asks.add(pool.submit(() -> {
						start.await();
						for (Class<?> type : order) {
							try {
								shared.getDescriptor(type);
							} catch (DataBindException refused) {
								// CircleHolder, Circle, TriangleHolder and Triangle are refused.
							}
						}
						return null;
					}));
				}
				for (Future<?> ask : asks) {
					ask.get();
				}
				assertEquals(Set.of(Point.class, Square.class, Line.class), members(shared, Shape.class),
						"round " + round);
				assertEquals(Set.of(), members(shared, Figure.class), "round " + round);
			}
		}
	}
}
