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
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.MapperShapes.ListOfOpenBranch;
import io.ltr8.test.data.MapperShapes.ListOfOpenUnion;
import io.ltr8.test.data.MapperShapes.ListOfUnion;
import io.ltr8.test.data.UnionTripwires;
import io.ltr8.test.data.union.InterfaceUnionPoint;
import io.ltr8.test.data.union.OpenBranchUnionCircle;
import io.ltr8.test.data.union.SealedInterfaceUnion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A union read refuses a class name its data gives for a class that cannot be a member before the class is
 * initialised or described, and reads every class that can, whether or not this context has seen it.
 */
class UnionReadMembershipTest {

	@Test
	void aClassOfNoUnionIsRefusedWithoutBeingInitialised() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();
		String message = notAMember(UnionTripwires.UNRELATED);
		assertAll(
				refused(message, () -> new ArrayMapper(context).toObject(ListOfUnion.class,
						arrayNaming(UnionTripwires.UNRELATED))),
				refused(message, () -> new MapMapper(context).toObject(ListOfUnion.class,
						mapNaming(UnionTripwires.UNRELATED))),
				() -> assertFalse(UnionTripwires.INITIALISED.contains("Unrelated")),
				() -> assertFalse(List.of(members(context)).contains(Class.forName(UnionTripwires.UNRELATED, false,
						getClass().getClassLoader()))));
	}

	/** A union listing its members admits no other class of its type, so it is refused before it is described. */
	@Test
	void aClassOfTheTypeOfAUnionThatDoesNotListItIsRefusedWithoutBeingInitialised() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();
		String message = notAMember(UnionTripwires.LISTED_OUT);
		assertAll(
				refused(message, () -> new ArrayMapper(context).toObject(ListOfUnion.class,
						arrayNaming(UnionTripwires.LISTED_OUT))),
				refused(message, () -> new MapMapper(context).toObject(ListOfUnion.class,
						mapNaming(UnionTripwires.LISTED_OUT))),
				() -> assertFalse(UnionTripwires.INITIALISED.contains("ListedOut")),
				() -> assertFalse(List.of(members(context)).contains(Class.forName(UnionTripwires.LISTED_OUT, false,
						getClass().getClassLoader()))));
	}

	/** An open {@code @Union} admits a class implementing it that this context has not described yet. */
	@Test
	void anOpenUnionReadsAClassThisContextHasNotDescribed() {
		String point = InterfaceUnionPoint.class.getName();
		ListOfOpenUnion expected = new ListOfOpenUnion(List.of(new InterfaceUnionPoint(1, 2)));
		assertAll(
				() -> assertEquals(expected, new ArrayMapper(DataBindContext.builder().build())
						.toObject(ListOfOpenUnion.class, arrayNaming(point))),
				() -> assertEquals(expected, new MapMapper(DataBindContext.builder().build())
						.toObject(ListOfOpenUnion.class, Map.of("shapes",
								new Object[] { Map.of("type", point, "x", 1, "y", 2) }))));
	}

	/** A sealed union's non-sealed branch admits an implementation this context has not described yet. */
	@Test
	void anOpenBranchReadsAnImplementationThisContextHasNotDescribed() {
		String circle = OpenBranchUnionCircle.class.getName();
		ListOfOpenBranch expected = new ListOfOpenBranch(List.of(new OpenBranchUnionCircle(1, 2)));
		assertAll(
				() -> assertEquals(expected, new ArrayMapper(DataBindContext.builder().build())
						.toObject(ListOfOpenBranch.class, arrayNaming(circle))),
				() -> assertEquals(expected, new MapMapper(DataBindContext.builder().build())
						.toObject(ListOfOpenBranch.class, Map.of("shapes",
								new Object[] { Map.of("type", circle, "x", 1, "radius", 2) }))));
	}

	/** A name of no class, and a map with no name, are refused as they are on a failure to convert. */
	@Test
	void aNameOfNoClassIsRefused() {
		String nothing = "io.ltr8.test.data.NoSuchClass";
		DataBindContext context = DataBindContext.builder().build();
		DataBindException fromArray = assertThrows(DataBindException.class,
				() -> new ArrayMapper(context).toObject(ListOfUnion.class, arrayNaming(nothing)));
		String fromMap = "Failed to convert Map to " + SealedInterfaceUnion.class + ".";
		assertAll(
				() -> assertEquals("Failed to convert class", fromArray.getMessage()),
				() -> assertInstanceOf(ClassNotFoundException.class, fromArray.getCause()),
				refused(fromMap, () -> new MapMapper(context).toObject(ListOfUnion.class, mapNaming(nothing))),
				refused(fromMap, () -> new MapMapper(context).toObject(ListOfUnion.class,
						Map.of("shapes", new Object[] { Map.of("x", 1, "y", 2) }))));
	}

	private static Class<?>[] members(DataBindContext context) throws DataBindException {
		return ((DataClassUnion) context.getDescriptor(SealedInterfaceUnion.class)).memberTypes();
	}

	private static Object[] arrayNaming(String member) {
		return new Object[] { new Object[] { new Object[] { member, new Object[] { 1, 2 } } } };
	}

	private static Map<String, Object> mapNaming(String member) {
		return Map.of("shapes", new Object[] { Map.of("type", member, "x", 1, "y", 2) });
	}

	private static String notAMember(String name) {
		return "'" + name + "' is not a member of union " + SealedInterfaceUnion.class.getName();
	}

	private static Executable refused(String message, Executable read) {
		return () -> assertEquals(message, assertThrows(DataBindException.class, read).getMessage());
	}
}
