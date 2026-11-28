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

import io.ltr8.annotation.Record;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sealed class an instance of which can be a member is listed beside the classes it permits. */
class ConcreteSealedMemberTest {

	/** The JDK seals an enum with a constant body, permitting the body's class; it stays an atom written by name. */
	@Test
	void anEnumWithAConstantBodyIsAnAtom() throws Throwable {
		DataBindContext context = DataBindContext.builder().build();
		assertAll(
				() -> assertTrue(Bodied.class.isSealed()),
				() -> assertEquals(String.class, ((DataClassAtom) context.getDescriptor(Bodied.class)).dataClass()),
				() -> assertEquals(String.class, ((DataClassAtom) context.getDescriptor(Operation.class)).dataClass()));
	}

	@Test
	void eachConstantOfAnEnumWithAConstantBodyRoundTrips() {
		assertAll(List.of(Bodied.values()).stream().map(constant -> () -> {
			EnumHolder holder = new EnumHolder(constant, Operation.PLUS);
			assertEquals(Map.of("bodied", constant.name(), "operation", "PLUS"),
					new MapMapper(DataBindContext.builder().build()).toMap(holder));
			roundTrip(EnumHolder.class, holder);
		}));
	}

	/** The enum is the member, never the class of a constant's body. */
	@Test
	void anEnumWithAConstantBodyIsAMemberOfItsUnion() throws DataBindException {
		DataClassUnion union = (DataClassUnion) DataBindContext.builder().build().getDescriptor(Choice.class);
		assertAll(
				() -> assertEquals(Set.of(Operation.class, Count.class), Set.of(union.memberTypes())),
				() -> assertTrue(union.isSealed()));
	}

	@Test
	void eachConstantOfAnEnumWithAConstantBodyRoundTripsAsAUnionValue() {
		assertAll(List.of(Operation.values()).stream().map(constant -> () -> roundTrip(ChoiceHolder.class,
				new ChoiceHolder(List.of(constant, new Count(1))))));
	}

	/** A sealed class described as a record is a member, and so is each class it permits. */
	@Test
	void aConcreteSealedClassBetweenTheRootAndTheLeavesIsAMember() throws DataBindException {
		DataClassUnion union = (DataClassUnion) DataBindContext.builder().build().getDescriptor(Top.class);
		assertAll(
				() -> assertEquals(Set.of(TopLeaf.class, Middle.class, MiddleLeaf.class),
						Set.of(union.memberTypes())),
				() -> assertTrue(union.isSealed(), "a sealed member's subclasses are all listed"));
	}

	@Test
	void anInstanceOfAConcreteSealedClassRoundTripsAsAUnionValue() {
		TopHolder holder = new TopHolder(List.of(new Middle(1), new MiddleLeaf(2, 3), new TopLeaf(4)));
		roundTrip(TopHolder.class, holder);
	}

	private static void roundTrip(Class<?> holderClass, Object holder) {
		assertAll(
				() -> {
					ArrayMapper mapper = new ArrayMapper(DataBindContext.builder().build());
					assertEquals(holder, mapper.toObject(holderClass, mapper.toArray(holder)));
				},
				() -> {
					MapMapper mapper = new MapMapper(DataBindContext.builder().build());
					Object read = mapper.toObject(holderClass, mapper.toMap(holder));
					assertInstanceOf(holderClass, read);
					assertEquals(holder, read);
				});
	}

	public enum Bodied {
		PLAIN,
		BODIED {
			@Override
			int weight() {
				return 2;
			}
		};

		int weight() {
			return 1;
		}
	}

	public sealed interface Choice permits Operation, Count {
	}

	/** Abstract, as an enum is whose every constant has a body. */
	public enum Operation implements Choice {
		PLUS {
			@Override
			int apply(int a, int b) {
				return a + b;
			}
		},
		MINUS {
			@Override
			int apply(int a, int b) {
				return a - b;
			}
		};

		abstract int apply(int a, int b);
	}

	public record Count(int n) implements Choice {
	}

	public record EnumHolder(Bodied bodied, Operation operation) {
	}

	public record ChoiceHolder(List<Choice> choices) {
	}

	public sealed interface Top permits TopLeaf, Middle {
	}

	public record TopLeaf(int t) implements Top {
	}

	public static sealed class Middle implements Top permits MiddleLeaf {
		private final int m;

		@Record
		public Middle(int m) {
			this.m = m;
		}

		public int m() {
			return m;
		}

		@Override
		public boolean equals(Object other) {
			return other != null && other.getClass() == getClass() && ((Middle) other).m == m;
		}

		@Override
		public int hashCode() {
			return m;
		}
	}

	public static final class MiddleLeaf extends Middle {
		private final int leaf;

		@Record
		public MiddleLeaf(int m, int leaf) {
			super(m);
			this.leaf = leaf;
		}

		public int leaf() {
			return leaf;
		}

		@Override
		public boolean equals(Object other) {
			return super.equals(other) && ((MiddleLeaf) other).leaf == leaf;
		}

		@Override
		public int hashCode() {
			return 31 * m() + leaf;
		}
	}

	public record TopHolder(List<Top> tops) {
	}
}
