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
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.analysis.CodeAnalysisException;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.AccessorAnalyses.AccessorBesidePublicFinalField;
import io.ltr8.test.data.AccessorAnalyses.EmptyUnionOnBoth;
import io.ltr8.test.data.AccessorAnalyses.InheritsGenericPublicField;
import io.ltr8.test.data.AccessorAnalyses.InheritsHiddenAccessor;
import io.ltr8.test.data.AccessorAnalyses.InheritsHiddenField;
import io.ltr8.test.data.AccessorAnalyses.InheritsPublicField;
import io.ltr8.test.data.AccessorAnalyses.Labelled;
import io.ltr8.test.data.AccessorAnalyses.NamedParameterOverPublicField;
import io.ltr8.test.data.AccessorAnalyses.NamedPublicFinalField;
import io.ltr8.test.data.AccessorAnalyses.OverridesPublicFieldAccessor;
import io.ltr8.test.data.AccessorAnalyses.OverridesRenamedShadowing;
import io.ltr8.test.data.AccessorAnalyses.OverridesShadowing;
import io.ltr8.test.data.AccessorAnalyses.OverridesThroughSuper;
import io.ltr8.test.data.AccessorAnalyses.PackagePrivateAccessor;
import io.ltr8.test.data.AccessorAnalyses.PackagePrivateAccessorOverPublicField;
import io.ltr8.test.data.AccessorAnalyses.PrivateAccessorBeforePublicAccessor;
import io.ltr8.test.data.AccessorAnalyses.PrivateAccessorOverPublicField;
import io.ltr8.test.data.AccessorAnalyses.ProtectedAccessor;
import io.ltr8.test.data.AccessorAnalyses.ProtectedAccessorOverPublicField;
import io.ltr8.test.data.AccessorAnalyses.PublicFinalList;
import io.ltr8.test.data.AccessorAnalyses.PublicFinalOptional;
import io.ltr8.test.data.AccessorAnalyses.PublicFinalPrimitives;
import io.ltr8.test.data.AccessorAnalyses.PublicFinalReference;
import io.ltr8.test.data.AccessorAnalyses.ReadsStatically;
import io.ltr8.test.data.AccessorAnalyses.ShadowsAfterSuper;
import io.ltr8.test.data.AccessorAnalyses.ShadowsBeforeSuper;
import io.ltr8.test.data.AccessorAnalyses.ShadowsNamedOnField;
import io.ltr8.test.data.AccessorAnalyses.ShadowsRenamedBase;
import io.ltr8.test.data.AccessorAnalyses.ShadowsUnnamed;
import io.ltr8.test.data.AccessorAnalyses.StaticMethodNamedForField;
import io.ltr8.test.data.AccessorAnalyses.StaticMethodOverPublicField;
import io.ltr8.test.data.AccessorAnalyses.UnionMembersDiffer;
import io.ltr8.test.data.AccessorAnalyses.UnionOnAccessor;
import io.ltr8.test.data.AccessorAnalyses.UnionOnBoth;
import io.ltr8.test.data.AccessorAnalyses.UnionOnComputingAccessor;
import io.ltr8.test.data.AccessorAnalyses.UnionOnFieldAndParameter;
import io.ltr8.test.data.AccessorAnalyses.UnionOnFieldDiffers;
import io.ltr8.test.data.AccessorAnalyses.UnionOnParameter;
import io.ltr8.test.data.AccessorAnalyses.UnionSealingDiffers;
import io.ltr8.test.data.AccessorAnalyses.Wraps;
import io.ltr8.test.data.RecordRefusals.PrivateAccessor;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hand-written immutable class's accessor reads the component whose field it returns: the field of that name the
 * accessor's own class sees, not another class's field of the same name.
 */
class AccessorAnalysisTest {

	private final DataBindContext context = DataBindContext.builder().build();

	// --- a super class and a subclass each declaring a private field of one name

	@Test
	void eachOfTwoFieldsOfOneNameIsReadByItsOwnClassAccessor() {
		assertAll(
				() -> assertEquals(List.of("value:String", "own:String"), fields(ShadowsAfterSuper.class)),
				() -> assertEquals(List.of("b", "s"), values(ShadowsAfterSuper.class, new ShadowsAfterSuper("b", "s"))),
				() -> assertEquals(List.of("own:String", "value:String"), fields(ShadowsBeforeSuper.class)),
				() -> assertEquals(List.of("s", "b"),
						values(ShadowsBeforeSuper.class, new ShadowsBeforeSuper("b", "s"))),
				() -> assertEquals(List.of("base:String", "value:String"), fields(ShadowsRenamedBase.class)),
				() -> assertEquals(List.of("b", "s"),
						values(ShadowsRenamedBase.class, new ShadowsRenamedBase("b", "s"))));
	}

	@Test
	void aFieldNamedApartOnTheFieldNamesItsOwnClassComponentAlone() {
		ShadowsNamedOnField value = new ShadowsNamedOnField("b", "s");
		assertAll(
				() -> assertEquals(List.of("value:String", "own:String"), fields(ShadowsNamedOnField.class)),
				() -> assertEquals(List.of("b", "s"), values(ShadowsNamedOnField.class, value)),
				() -> roundTrips(List.of(value)));
	}

	@Test
	void twoFieldsOfOneNameNotNamedApartAreRefused() {
		CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
				() -> context.getDescriptor(ShadowsUnnamed.class));
		assertEquals(ShadowsUnnamed.class.getName() + " has two fields named 'value'; @Field on an accessor names one "
				+ "apart", refused.getMessage());
		assertNull(refused.getCause());
	}

	// --- a subclass overriding its super class's accessor

	/** The super class's accessor would read the subclass's field, so its own field has no accessor. */
	@Test
	void anAccessorOverriddenByTheAccessorOfAnotherFieldReadsNoComponent() {
		assertAll(Stream.of(OverridesShadowing.class, OverridesRenamedShadowing.class).map(type -> () -> {
			CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
					() -> context.getDescriptor(type), type.getName());
			assertEquals("Failed to match immutable field accessor for class: " + type + ". Add @Field annotations "
					+ "to assist field 'value'", refused.getMessage());
			assertNull(refused.getCause());
		}));
	}

	@Test
	void anAccessorOverriddenByAMethodReadingNoFieldStillReadsItsComponent() {
		assertAll(
				() -> assertEquals(List.of("value:String"), fields(OverridesThroughSuper.class)),
				() -> assertEquals(List.of("v"), values(OverridesThroughSuper.class, new OverridesThroughSuper("v"))));
	}

	// --- a method that is not an accessor of this object's field

	@Test
	void aMethodReturningAFieldOfAnotherObjectReadsNoComponent() {
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(Wraps.class)),
				() -> assertEquals(List.of("x"), values(Wraps.class, new Wraps("x"))),
				() -> assertEquals(List.of("name:String"), fields(ReadsStatically.class)),
				() -> assertEquals(List.of("x"), values(ReadsStatically.class, new ReadsStatically("x"))));
	}

	@Test
	void aSubclassAccessorReadsAFieldItsSuperClassDeclares() {
		assertAll(
				() -> assertEquals(List.of("label:String"), fields(Labelled.class)),
				() -> assertEquals(List.of("tag"), values(Labelled.class, new Labelled("tag"))));
	}

	@Test
	void classesWithFieldsOfOneNameRoundTrip() {
		roundTrips(List.of(new ShadowsAfterSuper("b", "s"), new ShadowsBeforeSuper("b", "s"),
				new ShadowsRenamedBase("b", "s"), new Labelled("tag"), new Wraps("x"), new ReadsStatically("x"),
				new OverridesThroughSuper("v")));
	}

	// --- @Union on a constructor parameter, its field and its accessor

	@Test
	void unionsThatAgreeOrStandAloneDescribeTheUnion() {
		assertAll(Stream.of(UnionOnBoth.class, UnionOnParameter.class, UnionOnAccessor.class,
				UnionOnComputingAccessor.class, UnionOnFieldAndParameter.class).map(type -> () -> {
					DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
					assertEquals(1, record.fields().length, type.getName());
					DataClassField tag = record.fields()[0];
					assertEquals("tag", tag.name());
					DataClassUnion union = assertInstanceOf(DataClassUnion.class, tag.dataClass(), type.getName());
					assertEquals(List.of(String.class, Integer.class), List.of(union.memberTypes()), type.getName());
					assertTrue(union.isSealed(), type.getName());
				}));
	}

	@Test
	void unionsThatAgreeOrStandAloneRoundTrip() {
		roundTrips(List.of(new UnionOnBoth("x"), new UnionOnBoth(7), new UnionOnParameter("x"),
				new UnionOnAccessor(7), new UnionOnComputingAccessor("x"), new UnionOnFieldAndParameter(7)));
	}

	@Test
	void unionsThatDifferAreRefusedNamingTheField() {
		assertAll(Stream.of(UnionMembersDiffer.class, UnionSealingDiffers.class, UnionOnFieldDiffers.class)
				.map(type -> () -> {
					CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
							() -> context.getDescriptor(type), type.getName());
					assertEquals("Field 'tag' has @Union annotations that differ", refused.getMessage());
					assertNull(refused.getCause());
				}));
	}

	@Test
	void emptyUnionsThatAgreeAreRefusedAsEmpty() {
		CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
				() -> context.getDescriptor(EmptyUnionOnBoth.class));
		assertEquals("Union annotation must have one or more classes", refused.getMessage());
	}

	// --- the member that reads a constructor-bound field back: a public instance method, else a public final field

	@Test
	void aMethodThatIsNotAPublicInstanceMethodIsNoAccessorSoAFieldWithNoOtherIsRefused() {
		assertAll(Stream.of(PrivateAccessor.class, PackagePrivateAccessor.class, ProtectedAccessor.class,
				StaticMethodNamedForField.class).map(type -> () -> {
					CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
							() -> context.getDescriptor(type), type.getName());
					assertEquals("Failed to match immutable field accessor for class: " + type + ". Add @Field "
							+ "annotations to assist field 'name'", refused.getMessage());
					assertNull(refused.getCause());
				}));
	}

	@Test
	void aMethodThatIsNotAPublicInstanceMethodLeavesAPublicFinalFieldToBeReadDirectly() {
		assertAll(Stream.of(PrivateAccessorOverPublicField.class, PackagePrivateAccessorOverPublicField.class,
				ProtectedAccessorOverPublicField.class, StaticMethodOverPublicField.class).map(type -> () -> {
					assertEquals(List.of("name:String"), fields(type), type.getName());
					assertReadThroughField(field(type, 0), "name");
				}));
		assertAll(
				() -> assertEquals(List.of("v"),
						values(PrivateAccessorOverPublicField.class, new PrivateAccessorOverPublicField("v"))),
				() -> roundTrips(List.of(new PrivateAccessorOverPublicField("v"),
						new PackagePrivateAccessorOverPublicField("v"), new ProtectedAccessorOverPublicField("v"),
						new StaticMethodOverPublicField("v"))));
	}

	@Test
	void aPrivateMethodReturningTheFieldLeavesThePublicAccessorAfterItToReadIt() {
		PrivateAccessorBeforePublicAccessor value = new PrivateAccessorBeforePublicAccessor("v");
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(PrivateAccessorBeforePublicAccessor.class)),
				() -> assertReadThroughMethod(field(PrivateAccessorBeforePublicAccessor.class, 0), "name"),
				() -> assertEquals(List.of("v"), values(PrivateAccessorBeforePublicAccessor.class, value)),
				() -> roundTrips(List.of(value)));
	}

	@Test
	void aPublicFinalFieldWithNoAccessorIsReadDirectly() {
		PublicFinalPrimitives primitives = new PublicFinalPrimitives(3, 5_000_000_000L, 0.25, true);
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(PublicFinalReference.class)),
				() -> assertReadThroughField(field(PublicFinalReference.class, 0), "name"),
				() -> assertEquals(List.of("v"), values(PublicFinalReference.class, new PublicFinalReference("v"))),
				() -> assertEquals(List.of("count:int", "size:long", "ratio:double", "flag:boolean"),
						fields(PublicFinalPrimitives.class)),
				() -> assertReadThroughField(field(PublicFinalPrimitives.class, 1), "size"),
				() -> assertEquals(List.of(3, 5_000_000_000L, 0.25, true),
						values(PublicFinalPrimitives.class, primitives)),
				() -> assertEquals(List.of("items:List"), fields(PublicFinalList.class)),
				() -> assertEquals(List.of(List.of("a", "b")),
						values(PublicFinalList.class, new PublicFinalList(List.of("a", "b")))),
				() -> assertEquals(List.of("note:String"), fields(PublicFinalOptional.class)),
				() -> assertEquals(List.of("n"), values(PublicFinalOptional.class, new PublicFinalOptional(Optional.of("n")))));
	}

	@Test
	void aPublicFinalFieldWithNoAccessorRoundTripsOfEveryKind() {
		roundTrips(List.of(new PublicFinalReference("v"), new PublicFinalPrimitives(3, 5_000_000_000L, 0.25, true),
				new PublicFinalList(List.of("a", "b")), new PublicFinalList(List.of()),
				new PublicFinalOptional(Optional.of("n")), new PublicFinalOptional(Optional.empty())));
	}

	@Test
	void aPublicAccessorBesideAPublicFinalFieldIsTheReadSoTheAnnotationsOnItApply() {
		AccessorBesidePublicFinalField value = new AccessorBesidePublicFinalField("v");
		assertAll(
				() -> assertEquals(List.of("label:String"), fields(AccessorBesidePublicFinalField.class)),
				() -> assertReadThroughMethod(field(AccessorBesidePublicFinalField.class, 0), "name"),
				() -> roundTrips(List.of(value)));
	}

	@Test
	void aPublicFinalFieldNamedApartOnTheFieldIsReadDirectlyUnderThatName() {
		assertAll(
				() -> assertEquals(List.of("label:String"), fields(NamedPublicFinalField.class)),
				() -> assertReadThroughField(field(NamedPublicFinalField.class, 0), "name"),
				() -> roundTrips(List.of(new NamedPublicFinalField("v"))));
	}

	/** The component is what the field holds: the value as the constructor stored it. */
	@Test
	void aParameterNamedByFieldIsReadFromThePublicFinalFieldOfThatName() {
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(NamedParameterOverPublicField.class)),
				() -> assertEquals(List.of("v"),
						values(NamedParameterOverPublicField.class, new NamedParameterOverPublicField(" v "))),
				() -> roundTrips(List.of(new NamedParameterOverPublicField("v"))));
	}

	@Test
	void aPublicFinalFieldASuperClassDeclaresIsReadDirectlyThroughTheSuperCall() {
		assertAll(
				() -> assertEquals(List.of("name:String", "count:int"), fields(InheritsPublicField.class)),
				() -> assertEquals(List.of("n", 2), values(InheritsPublicField.class, new InheritsPublicField("n", 2))),
				() -> assertEquals(List.of("value:String"), fields(InheritsGenericPublicField.class)),
				() -> assertEquals(List.of("v"),
						values(InheritsGenericPublicField.class, new InheritsGenericPublicField("v"))),
				() -> roundTrips(List.of(new InheritsPublicField("n", 2), new InheritsGenericPublicField("v"))));
	}

	@Test
	void aSuperClassPublicFinalFieldWhoseAccessorASubclassOverridesIsReadDirectly() {
		OverridesPublicFieldAccessor value = new OverridesPublicFieldAccessor("b", "s");
		assertAll(
				() -> assertEquals(List.of("value:String", "own:String"), fields(OverridesPublicFieldAccessor.class)),
				() -> assertEquals(List.of("b", "s"), values(OverridesPublicFieldAccessor.class, value)),
				() -> roundTrips(List.of(value)));
	}

	@Test
	void everyFieldReadDirectlyTakesTheDescribedClass() {
		assertAll(Stream.of(PrivateAccessorOverPublicField.class, PublicFinalReference.class,
				PublicFinalPrimitives.class, PublicFinalList.class, PublicFinalOptional.class,
				NamedParameterOverPublicField.class, InheritsPublicField.class, InheritsGenericPublicField.class,
				OverridesPublicFieldAccessor.class).map(type -> () -> {
					DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
					for (DataClassField field : record.fields()) {
						assertEquals(type, field.accessor().type().parameterType(0), type.getName() + " " + field.name());
					}
				}));
	}

	@Test
	void aMemberOfASuperClassThePublicLookupCannotReachIsRefusedNamingTheClassAndMember() {
		assertAll(Stream.of(InheritsHiddenField.class, InheritsHiddenAccessor.class).map(type -> () -> {
			CodeAnalysisException refused = assertThrows(CodeAnalysisException.class,
					() -> context.getDescriptor(type), type.getName());
			String message = refused.getMessage();
			assertTrue(message.startsWith(type.getName() + " is bound through a member that is not accessible: class "
					+ "is not public: " + type.getSuperclass().getName() + ".name"), message);
			IllegalAccessException cause = assertInstanceOf(IllegalAccessException.class, refused.getCause());
			assertTrue(message.endsWith(cause.getMessage()), message);
		}));
	}

	// --- helpers

	private DataClassField field(Class<?> type, int index) throws DataBindException {
		return assertInstanceOf(DataClassRecord.class, context.getDescriptor(type)).fields()[index];
	}

	private static void assertReadThroughField(DataClassField field, String name) {
		MethodHandleInfo read = MethodHandles.lookup().revealDirect(field.accessor());
		assertEquals(MethodHandleInfo.REF_getField, read.getReferenceKind(), field.name());
		assertEquals(name, read.getName(), field.name());
	}

	private static void assertReadThroughMethod(DataClassField field, String name) {
		MethodHandleInfo read = MethodHandles.lookup().revealDirect(field.accessor());
		assertEquals(MethodHandleInfo.REF_invokeVirtual, read.getReferenceKind(), field.name());
		assertEquals(name, read.getName(), field.name());
	}

	private void roundTrips(List<Object> values) {
		MapMapper maps = new MapMapper(context);
		ArrayMapper arrays = new ArrayMapper(context);
		assertAll(values.stream().flatMap(value -> {
			Class<?> type = value.getClass();
			return Stream.of(
					() -> assertEquals(value, maps.toObject(type, maps.toMap(value)), "map " + type.getName()),
					() -> assertEquals(value, arrays.toObject(type, arrays.toArray(value)), "array " + type.getName()));
		}));
	}

	private List<Object> values(Class<?> type, Object value) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		return Arrays.stream(record.fields()).map(field -> field.get(value)).toList();
	}

	private List<String> fields(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		return Arrays.stream(record.fields()).map(AccessorAnalysisTest::describe).toList();
	}

	private static String describe(DataClassField field) {
		return field.name() + ":" + field.type().getSimpleName();
	}
}
