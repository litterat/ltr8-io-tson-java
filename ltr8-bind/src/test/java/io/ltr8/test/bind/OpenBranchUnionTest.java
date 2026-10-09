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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.test.data.union.OpenBranchUnion;
import io.ltr8.test.data.union.OpenBranchUnionCircle;
import io.ltr8.test.data.union.OpenBranchUnionHolder;
import io.ltr8.test.data.union.OpenBranchUnionPoint;
import io.ltr8.test.data.union.OpenBranchUnionShape;
import io.ltr8.test.data.union.OpenBranchUnionSquare;

/**
 * A sealed union with a <b>non-sealed branch</b> -- the one union shape the rest of this suite does not
 * cover, and the one where membership by exact class gives the wrong answer.
 *
 * <p>{@code DefaultUnionBinder} flattens a sealed hierarchy to its leaves but keeps a non-sealed permitted
 * type as a member in its own right, because its implementations cannot be known at analysis time; that is
 * also why the resulting union is built extensible ({@code isSealed == false}) and why
 * {@link DataClassUnion#addMemberType} exists. What was missing was the join: nothing resolved an
 * implementation against the member standing for it, so {@code isMemberType} answered {@code false} for
 * every one of them and a value that is plainly a member could be neither constructed with nor written.
 */
public class OpenBranchUnionTest {

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	private DataClassUnion union() throws DataBindException {
		return (DataClassUnion) context.getDescriptor(OpenBranchUnion.class);
	}

	/** The shape: one closed leaf, and the non-sealed branch kept as a member rather than walked into. */
	@Test
	public void theOpenBranchIsItselfAMemberAndTheUnionStaysExtensible() throws DataBindException {
		DataClassUnion union = union();

		Assertions.assertFalse(union.isSealed(), "a union collected from a sealed hierarchy stays extensible");
		Assertions.assertTrue(union.isMemberType(OpenBranchUnionPoint.class), "the closed leaf is a member");
		Assertions.assertTrue(union.isMemberType(OpenBranchUnionShape.class),
				"the non-sealed branch is kept as a member rather than walked into");
		Assertions.assertEquals(2, union.memberTypes().length, "and those two are all that analysis can know");
	}

	/**
	 * The rule: an implementation of the open branch is a member, though analysis never listed it. This is
	 * the question that used to answer {@code false} -- the member standing for it was never consulted.
	 */
	@Test
	public void anImplementationOfTheOpenBranchIsAMember() throws DataBindException {
		Assertions.assertTrue(union().isMemberType(OpenBranchUnionCircle.class));
	}

	/**
	 * And asking registers it, so the class becomes reachable through {@link DataClassUnion#memberTypes}.
	 * That is what makes the membership usable rather than merely acknowledged: a caller resolves the
	 * class's own descriptor from there, so the value is written and read as itself rather than as the
	 * abstract branch it arrived under.
	 */
	@Test
	public void askingRegistersTheImplementation() throws DataBindException {
		DataClassUnion union = union();
		Assertions.assertFalse(List.of(union.memberTypes()).contains(OpenBranchUnionCircle.class),
				"not listed before the question is asked");

		union.isMemberType(OpenBranchUnionCircle.class);

		Assertions.assertTrue(List.of(union.memberTypes()).contains(OpenBranchUnionCircle.class));
		Assertions.assertEquals(OpenBranchUnionCircle.class,
				context.getDescriptor(OpenBranchUnionCircle.class).typeClass());
	}

	/** Memoisation, not a change of answer: asking twice gives the same result and adds one member. */
	@Test
	public void askingTwiceAddsOneMember() throws DataBindException {
		DataClassUnion union = union();

		Assertions.assertTrue(union.isMemberType(OpenBranchUnionCircle.class));
		int afterFirst = union.memberTypes().length;
		Assertions.assertTrue(union.isMemberType(OpenBranchUnionCircle.class));

		Assertions.assertEquals(afterFirst, union.memberTypes().length);
	}

	/** Several implementations may join the same branch, each keeping its own identity. */
	@Test
	public void severalImplementationsMayJoinTheSameOpenBranch() throws DataBindException {
		DataClassUnion union = union();

		Assertions.assertTrue(union.isMemberType(OpenBranchUnionCircle.class));
		Assertions.assertTrue(union.isMemberType(OpenBranchUnionSquare.class));
		Assertions.assertEquals(4, union.memberTypes().length);
	}

	/** A class implementing nothing in the union is refused, and the attempt registers nothing. */
	@Test
	public void anUnrelatedClassIsNotAMember() throws DataBindException {
		DataClassUnion union = union();

		Assertions.assertFalse(union.isMemberType(String.class));
		Assertions.assertEquals(2, union.memberTypes().length);
	}

	/** {@code null} is not a member, as it never was. */
	@Test
	public void nullIsNotAMember() throws DataBindException {
		Assertions.assertFalse(union().isMemberType(null));
	}

	/** The construct/read path asks the same question, so an implementation reaches a constructor slot. */
	@Test
	public void checkIsMemberAcceptsAnImplementationOfTheOpenBranch() throws DataBindException {
		OpenBranchUnion value = new OpenBranchUnionCircle(1, 2);

		Assertions.assertSame(value, union().checkIsMember(value));
	}

	/** And still rejects what is genuinely not a member, with the union's members named. */
	@Test
	public void checkIsMemberStillRejectsAnUnrelatedValue() throws DataBindException {
		DataClassUnion union = union();

		DataBindException thrown =
				Assertions.assertThrows(DataBindException.class, () -> union.checkIsMember("not a shape"));

		Assertions.assertTrue(thrown.getMessage().contains("not in valid types"), thrown.getMessage());
	}

	/**
	 * The round trip through the bind engine, which is where the rule has to hold to be worth anything: the
	 * constructor slot goes through {@code checkIsMember}'s bound handle and the list and array elements go
	 * through {@code ArrayMapper}'s own membership check. All three carry implementations of the open
	 * branch, and all three come back as themselves.
	 */
	@Test
	public void aRecordCarryingImplementationsOfTheOpenBranchRoundTrips() throws Throwable {
		OpenBranchUnion circle = new OpenBranchUnionCircle(1, 2);
		OpenBranchUnion square = new OpenBranchUnionSquare(3, 4);
		OpenBranchUnionHolder holder = new OpenBranchUnionHolder(circle,
				java.util.List.of(circle, square), new OpenBranchUnion[] { square, circle });

		ArrayMapper mapper = new ArrayMapper(context);
		OpenBranchUnionHolder result =
				mapper.toObject(OpenBranchUnionHolder.class, mapper.toArray(holder));

		Assertions.assertEquals(circle, result.value());
		Assertions.assertEquals(List.of(circle, square), result.list());
		Assertions.assertArrayEquals(new OpenBranchUnion[] { square, circle }, result.array());
	}

	/**
	 * The read direction on its own. The round trip above writes before it reads, so the write pass has
	 * already registered the implementation by the time the read asks -- which hides whether the read side
	 * applies the rule itself. Reading into a <em>fresh</em> context, whose union has never seen the class,
	 * is what actually tests it.
	 */
	@Test
	public void readingIntoAFreshContextAdmitsTheImplementationToo() throws Throwable {
		OpenBranchUnion circle = new OpenBranchUnionCircle(1, 2);
		OpenBranchUnionHolder holder = new OpenBranchUnionHolder(circle, List.of(circle),
				new OpenBranchUnion[] { circle });

		Object[] values = new ArrayMapper(context).toArray(holder);

		ArrayMapper reader = new ArrayMapper(DataBindContext.builder().build());
		OpenBranchUnionHolder result = reader.toObject(OpenBranchUnionHolder.class, values);

		Assertions.assertEquals(circle, result.value());
		Assertions.assertEquals(List.of(circle), result.list());
	}

}
