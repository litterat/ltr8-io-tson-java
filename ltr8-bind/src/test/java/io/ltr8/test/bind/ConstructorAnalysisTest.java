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
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.analysis.CodeAnalysisException;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.ConstructorAnalyses.DelegateRepeats;
import io.ltr8.test.data.ConstructorAnalyses.Delegates;
import io.ltr8.test.data.ConstructorAnalyses.DelegatesConstant;
import io.ltr8.test.data.ConstructorAnalyses.DelegatesToSuper;
import io.ltr8.test.data.ConstructorAnalyses.DelegatesToTransforming;
import io.ltr8.test.data.ConstructorAnalyses.DelegatesTwice;
import io.ltr8.test.data.ConstructorAnalyses.DelegatesWide;
import io.ltr8.test.data.ConstructorAnalyses.Increments;
import io.ltr8.test.data.ConstructorAnalyses.NamedDelegatesConstant;
import io.ltr8.test.data.ConstructorAnalyses.NamedReassigns;
import io.ltr8.test.data.ConstructorAnalyses.NamedReassignsBeforeStore;
import io.ltr8.test.data.ConstructorAnalyses.NamedReassignsOne;
import io.ltr8.test.data.ConstructorAnalyses.PassesToTrimming;
import io.ltr8.test.data.ConstructorAnalyses.Reassigns;
import io.ltr8.test.data.ConstructorAnalyses.ReassignsAfterStore;
import io.ltr8.test.data.ConstructorAnalyses.ReassignsAndDrops;
import io.ltr8.test.data.ConstructorAnalyses.ReassignsBeforeStore;
import io.ltr8.test.data.ConstructorAnalyses.ReassignsOne;
import io.ltr8.test.data.ConstructorAnalyses.ReassignsWide;
import io.ltr8.test.data.ConstructorAnalyses.StoresRepeated;
import io.ltr8.test.data.ConstructorAnalyses.SuperRepeats;
import io.ltr8.test.data.ConstructorAnalyses.TrimmingBase;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A hand-written immutable class is described only from parameters its constructor stores unchanged; anything else
 * is matched by {@code @Field} or refused.
 */
class ConstructorAnalysisTest {

	private final DataBindContext context = DataBindContext.builder().build();

	// --- a parameter the constructor assigns

	@Test
	void aParameterTheConstructorAssignsIsUnmatchedWithoutField() {
		assertAll(Stream.of(Reassigns.class, ReassignsBeforeStore.class, ReassignsWide.class, Increments.class,
				ReassignsOne.class, ReassignsAndDrops.class, ReassignsAfterStore.class).map(type -> () -> {
					CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
							() -> context.getDescriptor(type), type.getName());
					assertEquals(unmatched(type), refused.getMessage());
					assertNull(refused.getCause());
				}));
	}

	@Test
	void aSuperConstructorThatAssignsItsParameterIsRefusedNamingTheClassThatPassesIt() {
		CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
				() -> context.getDescriptor(PassesToTrimming.class));
		assertEquals(PassesToTrimming.class.getName() + "'s constructor calls " + TrimmingBase.class.getName()
				+ ".<init>(java.lang.String), which does not store its parameter 0 unchanged in a field",
				refused.getMessage());
		assertNull(refused.getCause());
	}

	/**
	 * The component is what the accessor reads: the value as the constructor stored it. One {@code @Field} names
	 * follows those the analysis matched.
	 */
	@Test
	void anAssignedParameterNamedByFieldReadsTheStoredValue() {
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(NamedReassigns.class)),
				() -> assertEquals(List.of("name:String"), fields(NamedReassignsBeforeStore.class)),
				() -> assertEquals(List.of("count:int", "name:String"), fields(NamedReassignsOne.class)),
				() -> assertEquals(List.of("x"), values(NamedReassigns.class, new NamedReassigns(" x "))),
				() -> assertEquals(List.of("x"),
						values(NamedReassignsBeforeStore.class, new NamedReassignsBeforeStore(" x "))),
				() -> assertEquals(List.of(2, "x"),
						values(NamedReassignsOne.class, new NamedReassignsOne(" x ", 2))));
	}

	@Test
	void anAssignedParameterNamedByFieldRoundTripsTheStoredValue() {
		List<Object> trimmed = List.of(new NamedReassigns("x"), new NamedReassignsBeforeStore("x"),
				new NamedReassignsOne("x", 2));
		List<Object> padded = List.of(new NamedReassigns(" x "), new NamedReassignsBeforeStore(" x "),
				new NamedReassignsOne(" x ", 2));
		roundTrips(padded, trimmed);
	}

	// --- a constructor that delegates with this(…)

	@Test
	void aDelegateCallPassingOnlyOwnParametersBindsEachToTheFieldItsDelegateStores() {
		assertAll(
				() -> assertEquals(List.of("a:String", "b:Integer"), fields(Delegates.class)),
				() -> assertEquals(List.of("x", 2), values(Delegates.class, new Delegates("x", 2))),
				() -> assertEquals(List.of("name:String", "size:long", "ratio:double"), fields(DelegatesWide.class)),
				() -> assertEquals(List.of("bin", 7_000_000_000L, 0.5),
						values(DelegatesWide.class, new DelegatesWide(0.5, 7_000_000_000L, "bin"))));
	}

	@Test
	void aDelegateThatCallsSuperBindsTheSuperFieldsFirst() {
		assertAll(
				() -> assertEquals(List.of("name:String", "count:int"), fields(DelegatesToSuper.class)),
				() -> assertEquals(List.of("rug", 3), values(DelegatesToSuper.class, new DelegatesToSuper(3, "rug"))));
	}

	/** A component takes the type of the designated constructor's parameter, not the delegate's. */
	@Test
	void aDelegateTwoDeepBindsTheDesignatedConstructorParameterTypes() {
		assertAll(
				() -> assertEquals(List.of("a:String", "b:Integer"), fields(DelegatesTwice.class)),
				() -> assertEquals(List.of("x", 2), values(DelegatesTwice.class, new DelegatesTwice("x", 2))));
	}

	@Test
	void everyFieldHandleOfADelegatingClassTakesThatClass() {
		assertAll(Stream.of(Delegates.class, DelegatesToSuper.class, DelegatesTwice.class).map(type -> () -> {
			DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
			for (DataClassField field : record.fields()) {
				assertEquals(type, field.accessor().type().parameterType(0), field.name() + " accessor");
				assertEquals(type, field.isPresent().type().parameterType(0), field.name() + " isPresent");
			}
		}));
	}

	@Test
	void aDelegateCallPassingAConstantBindsNothing() {
		CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
				() -> context.getDescriptor(DelegatesConstant.class));
		assertEquals(unmatched(DelegatesConstant.class), refused.getMessage());
		assertNull(refused.getCause());
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(NamedDelegatesConstant.class)),
				() -> assertEquals(List.of("x"),
						values(NamedDelegatesConstant.class, new NamedDelegatesConstant("x"))));
	}

	@Test
	void aDelegateThatDoesNotStoreAnArgumentItIsPassedIsRefusedNamingTheCall() {
		CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
				() -> context.getDescriptor(DelegatesToTransforming.class));
		String name = DelegatesToTransforming.class.getName();
		assertEquals(name + "'s constructor calls " + name + ".<init>(java.lang.CharSequence), which does not store "
				+ "its parameter 0 unchanged in a field", refused.getMessage());
		assertNull(refused.getCause());
	}

	@Test
	void aDelegatingClassRoundTrips() {
		List<Object> values = List.of(new Delegates("x", 2), new Delegates(null, null),
				new DelegatesWide(0.5, 7_000_000_000L, "bin"), new DelegatesToSuper(3, "rug"),
				new DelegatesTwice("x", 2), new NamedDelegatesConstant("x"));
		roundTrips(values, values);
	}

	// --- each parameter reaches exactly one field

	@Test
	void aParameterStoredInTwoFieldsIsRefusedNamingTheParameter() {
		assertAll(Stream.of(SuperRepeats.class, DelegateRepeats.class, StoresRepeated.class).map(type -> () -> {
			CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
					() -> context.getDescriptor(type), type.getName());
			assertEquals(type.getName() + "'s constructor stores its parameter 0 in more than one field",
					refused.getMessage());
			assertNull(refused.getCause());
		}));
	}

	// --- helpers

	private void roundTrips(List<Object> written, List<Object> read) {
		MapMapper maps = new MapMapper(context);
		ArrayMapper arrays = new ArrayMapper(context);
		assertAll(Stream.iterate(0, x -> x < written.size(), x -> x + 1).flatMap(x -> {
			Object value = written.get(x);
			Class<?> type = value.getClass();
			return Stream.of(
					() -> assertEquals(read.get(x), maps.toObject(type, maps.toMap(value)), "map " + type.getName()),
					() -> assertEquals(read.get(x), arrays.toObject(type, arrays.toArray(value)),
							"array " + type.getName()));
		}));
	}

	private static String unmatched(Class<?> type) {
		return "Failed to match immutable fields for class: " + type + ". Add @Field annotations to assist.";
	}

	private List<Object> values(Class<?> type, Object value) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		return Arrays.stream(record.fields()).map(field -> field.get(value)).toList();
	}

	private List<String> fields(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		return Arrays.stream(record.fields()).map(ConstructorAnalysisTest::describe).toList();
	}

	private static String describe(DataClassField field) {
		return field.name() + ":" + field.type().getSimpleName();
	}
}
