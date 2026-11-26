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
import io.ltr8.test.data.SuperConstructors.Counted;
import io.ltr8.test.data.SuperConstructors.FixedKind;
import io.ltr8.test.data.SuperConstructors.HoldsCounted;
import io.ltr8.test.data.SuperConstructors.HoldsSized;
import io.ltr8.test.data.SuperConstructors.Layered;
import io.ltr8.test.data.SuperConstructors.NamedPassesBoxed;
import io.ltr8.test.data.SuperConstructors.NamedPassesComputed;
import io.ltr8.test.data.SuperConstructors.NamedPassesConstant;
import io.ltr8.test.data.SuperConstructors.NamedPassesConstantFirst;
import io.ltr8.test.data.SuperConstructors.NamedPassesWidened;
import io.ltr8.test.data.SuperConstructors.NarrowsSuper;
import io.ltr8.test.data.SuperConstructors.OffsetSuper;
import io.ltr8.test.data.SuperConstructors.PassesBoxed;
import io.ltr8.test.data.SuperConstructors.PassesComputed;
import io.ltr8.test.data.SuperConstructors.PassesConstant;
import io.ltr8.test.data.SuperConstructors.PassesConstantFirst;
import io.ltr8.test.data.SuperConstructors.PassesWidened;
import io.ltr8.test.data.SuperConstructors.ReachesConstant;
import io.ltr8.test.data.SuperConstructors.Resized;
import io.ltr8.test.data.SuperConstructors.Sized;
import io.ltr8.test.data.SuperConstructors.SwappedPair;
import io.ltr8.test.data.SuperConstructors.TaggedCount;
import io.ltr8.test.data.SuperConstructors.TextValue;
import io.ltr8.test.data.SuperConstructors.Tiered;
import io.ltr8.test.data.SuperConstructors.WideField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A hand-written immutable class whose constructor passes arguments, unchanged, to a public super class constructor
 * is described with the super class's fields as well as its own, and binds both ways.
 */
class SuperConstructorTest {

	private static final Counted COUNTED = new Counted("widget", 3);

	private static final Sized SIZED = new Sized("crate", 5_000_000_000L, 0.25);

	/** Its inherited field is absent, so its writer takes the branch that leaves the slot empty. */
	private static final Counted UNNAMED = new Counted(null, 7);

	private static final OffsetSuper OFFSET = new OffsetSuper("extra", "name");

	private static final SwappedPair SWAPPED = new SwappedPair("second", "first");

	private static final Resized RESIZED = new Resized(0.5, 7_000_000_000L, "bin");

	private static final Layered LAYERED = new Layered("rug", 2, "red");

	private static final Tiered TIERED = new Tiered(true, "blue", 4, "stack");

	private static final NarrowsSuper NARROWS = new NarrowsSuper("narrow");

	private static final TextValue TEXT_VALUE = new TextValue("erased");

	private static final WideField WIDE_FIELD = new WideField("wide");

	private static final FixedKind FIXED_KIND = new FixedKind(1.5);

	private static final NamedPassesComputed NAMED_COMPUTED = new NamedPassesComputed(" padded ");

	private static final List<Object> VALUES = List.of(COUNTED, SIZED, UNNAMED,
			new TaggedCount(Optional.of("red"), 2), new TaggedCount(Optional.empty(), 4),
			new HoldsSized("box", SIZED), new HoldsCounted(UNNAMED, 2), OFFSET, SWAPPED, RESIZED, LAYERED, TIERED,
			NARROWS, TEXT_VALUE, WIDE_FIELD, FIXED_KIND, new NamedPassesConstant("named"),
			new NamedPassesConstantFirst("tag"), NAMED_COMPUTED, new NamedPassesWidened(3), new NamedPassesBoxed(4));

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void theSuperClassFieldsComeBeforeTheClassOwn() throws DataBindException {
		assertEquals(List.of("name:String", "count:int"), fields(Counted.class));
	}

	@Test
	void argumentsTakingTwoLocalSlotsAreMatchedToTheirFields() throws DataBindException {
		assertEquals(List.of("name:String", "size:long", "ratio:double"), fields(Sized.class));
	}

	@Test
	void eachFieldReadsTheValueItsConstructorArgumentSet() throws DataBindException {
		assertEquals(List.of("crate", 5_000_000_000L, 0.25), values(Sized.class, SIZED));
	}

	@ParameterizedTest
	@ValueSource(classes = { Counted.class, Sized.class, TaggedCount.class, OffsetSuper.class, Tiered.class,
			NarrowsSuper.class, TextValue.class, NamedPassesWidened.class })
	void everyFieldHandleTakesTheDescribedClass(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		for (DataClassField field : record.fields()) {
			assertEquals(type, field.accessor().type().parameterType(0), field.name() + " accessor");
			assertEquals(type, field.isPresent().type().parameterType(0), field.name() + " isPresent");
		}
	}

	@Test
	void roundTripsThroughAnArray() throws Throwable {
		ArrayMapper mapper = new ArrayMapper(context);
		for (Object value : VALUES) {
			assertEquals(value, mapper.toObject(value.getClass(), mapper.toArray(value)), value.getClass().getName());
		}
	}

	@Test
	void anArrayHoldsTheSuperClassFieldsFirst() throws Throwable {
		ArrayMapper mapper = new ArrayMapper(context);
		assertEquals(List.of("widget", 3), Arrays.asList(mapper.toArray(COUNTED)));
		assertEquals(Arrays.asList(null, 7), Arrays.asList(mapper.toArray(UNNAMED)));
	}

	@Test
	void roundTripsThroughAMap() throws DataBindException {
		MapMapper mapper = new MapMapper(context);
		for (Object value : VALUES) {
			assertEquals(value, mapper.toObject(value.getClass(), mapper.toMap(value)), value.getClass().getName());
		}
	}

	// --- arguments passed up out of their own order

	@Test
	void aSuperArgumentThatIsNotLeadingBindsTheParameterPassedToIt() throws DataBindException {
		assertEquals(List.of("name:String", "extra:String"), fields(OffsetSuper.class));
		assertEquals(List.of("name", "extra"), values(OffsetSuper.class, OFFSET));
	}

	@Test
	void superArgumentsInTheOppositeOrderEachBindTheirOwnParameter() {
		assertAll(
				() -> assertEquals(List.of("first:String", "second:String"), fields(SwappedPair.class)),
				() -> assertEquals(List.of("first", "second"), values(SwappedPair.class, SWAPPED)),
				() -> assertEquals(List.of("name:String", "size:long", "ratio:double"), fields(Resized.class)),
				() -> assertEquals(List.of("bin", 7_000_000_000L, 0.5), values(Resized.class, RESIZED)));
	}

	// --- super constructors more than one deep

	@Test
	void aClassTwoSuperConstructorsDeepListsEveryLevelFromTheTop() throws DataBindException {
		assertEquals(List.of("name:String", "count:int", "colour:String"), fields(Layered.class));
		assertEquals(List.of("rug", 2, "red"), values(Layered.class, LAYERED));
	}

	@Test
	void aClassThreeSuperConstructorsDeepBindsEachArgumentFromItsOwnPosition() throws DataBindException {
		assertEquals(List.of("name:String", "count:int", "colour:String", "flag:boolean"), fields(Tiered.class));
		assertEquals(List.of("stack", 4, "blue", true), values(Tiered.class, TIERED));
	}

	// --- a parameter type narrower than the type it is stored as

	@Test
	void aParameterStoredAsAWiderTypeBindsAsTheConstructorParameterType() {
		assertAll(
				() -> assertEquals(List.of("text:String"), fields(NarrowsSuper.class)),
				() -> assertEquals(List.of("value:String"), fields(TextValue.class)),
				() -> assertEquals(List.of("text:String"), fields(WideField.class)),
				() -> assertEquals(List.of("narrow"), values(NarrowsSuper.class, NARROWS)),
				() -> assertEquals(List.of("erased"), values(TextValue.class, TEXT_VALUE)));
	}

	// --- a super call with an argument that is not one of the constructor's own parameters, unchanged

	@Test
	void aSuperFieldFixedByAConstantIsNotAComponent() throws DataBindException {
		assertEquals(List.of("radius:double"), fields(FixedKind.class));
		assertEquals(List.of(1.5), values(FixedKind.class, FIXED_KIND));
	}

	private static String unmatched(Class<?> type) {
		return "Failed to match immutable fields for class: " + type + ". Add @Field annotations to assist.";
	}

	@Test
	void aParameterTheSuperCallDoesNotPassUnchangedIsUnmatchedWithoutField() {
		assertAll(Stream.of(PassesConstant.class, PassesConstantFirst.class, PassesComputed.class,
				PassesWidened.class, PassesBoxed.class).map(type -> () -> {
					CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
							() -> context.getDescriptor(type));
					assertEquals(unmatched(type), refused.getMessage());
					assertNull(refused.getCause());
				}));
	}

	@Test
	void aParameterNamedByFieldIsReadThroughTheSuperAccessorOfThatName() {
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(NamedPassesConstant.class)),
				() -> assertEquals(List.of("tag:String"), fields(NamedPassesConstantFirst.class)),
				() -> assertEquals(List.of("name:String"), fields(NamedPassesComputed.class)),
				() -> assertEquals(List.of("size:int"), fields(NamedPassesWidened.class)),
				() -> assertEquals(List.of("size:int"), fields(NamedPassesBoxed.class)),
				() -> assertEquals(List.of(3), values(NamedPassesWidened.class, new NamedPassesWidened(3))),
				() -> assertEquals(List.of(4), values(NamedPassesBoxed.class, new NamedPassesBoxed(4))));
	}

	/** The component is what the accessor reads: the value as the constructor stored it. */
	@Test
	void aParameterNamedByFieldThroughAComputedSuperArgumentReadsTheStoredValue() throws DataBindException {
		assertEquals(List.of("padded"), values(NamedPassesComputed.class, NAMED_COMPUTED));
	}

	@Test
	void aSuperConstructorThatStoresNoneOfAnArgumentItIsPassedIsRefusedNamingTheClassThatPassesIt() {
		CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
				() -> context.getDescriptor(ReachesConstant.class));
		assertEquals(ReachesConstant.class.getName() + "'s constructor calls " + PassesConstant.class.getName()
				+ ".<init>(java.lang.String), which does not store its parameter 0 unchanged in a field",
				refused.getMessage());
		assertNull(refused.getCause());
	}

	private List<Object> values(Class<?> type, Object value) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		return Arrays.stream(record.fields()).map(field -> field.get(value)).toList();
	}

	private List<String> fields(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		return Arrays.stream(record.fields()).map(SuperConstructorTest::describe).toList();
	}

	private static String describe(DataClassField field) {
		return field.name() + ":" + field.type().getSimpleName();
	}
}
