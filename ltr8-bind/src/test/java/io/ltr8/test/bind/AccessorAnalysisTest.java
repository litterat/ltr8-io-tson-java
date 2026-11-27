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
import io.ltr8.test.data.AccessorAnalyses.BindsOnePublicMutableField;
import io.ltr8.test.data.AccessorAnalyses.EmptyUnionOnBoth;
import io.ltr8.test.data.AccessorAnalyses.InheritsGenericPublicField;
import io.ltr8.test.data.AccessorAnalyses;
import io.ltr8.test.data.AccessorAnalyses.InheritsAcrossHidden;
import io.ltr8.test.data.AccessorAnalyses.InheritsHiddenAccessor;
import io.ltr8.test.data.AccessorAnalyses.InheritsHiddenField;
import io.ltr8.test.data.AccessorAnalyses.InheritsHiddenNamedAccessor;
import io.ltr8.test.data.AccessorAnalyses.InheritsHiddenTwoUp;
import io.ltr8.test.data.AccessorAnalyses.InheritsShadowedHiddenField;
import io.ltr8.test.data.AccessorAnalyses.InheritsPublicField;
import io.ltr8.test.data.AccessorAnalyses.InheritsPublicMutableField;
import io.ltr8.test.data.AccessorAnalyses.Labelled;
import io.ltr8.test.data.AccessorAnalyses.NamedParameterOverPublicField;
import io.ltr8.test.data.AccessorAnalyses.NamedPublicFinalField;
import io.ltr8.test.data.AccessorAnalyses.NamedPublicMutableField;
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
import io.ltr8.test.data.AccessorAnalyses.PublicMutableList;
import io.ltr8.test.data.AccessorAnalyses.PublicMutableOptional;
import io.ltr8.test.data.AccessorAnalyses.PublicMutablePrimitives;
import io.ltr8.test.data.AccessorAnalyses.PublicMutableReference;
import io.ltr8.test.data.AccessorAnalyses.ReadsStatically;
import io.ltr8.test.data.AccessorAnalyses.ShadowsAfterSuper;
import io.ltr8.test.data.AccessorAnalyses.ShadowsBeforeSuper;
import io.ltr8.test.data.AccessorAnalyses.ShadowsNamedOnField;
import io.ltr8.test.data.AccessorAnalyses.ShadowsRenamedBase;
import io.ltr8.test.data.AccessorAnalyses.ShadowsUnnamed;
import io.ltr8.test.data.AccessorAnalyses.StaticMethodNamedForField;
import io.ltr8.test.data.AccessorAnalyses.StoresAnnotatedInheritedFields;
import io.ltr8.test.data.AccessorAnalyses.StoresBothFieldsOfOneName;
import io.ltr8.test.data.AccessorAnalyses.StoresHiddenInheritedField;
import io.ltr8.test.data.AccessorAnalyses.StoresHiddenInheritedPublicField;
import io.ltr8.test.data.AccessorAnalyses.StoresHiddenSuperField;
import io.ltr8.test.data.AccessorAnalyses.StoresInheritedField;
import io.ltr8.test.data.AccessorAnalyses.StoresInheritedFieldReadBySubclass;
import io.ltr8.test.data.AccessorAnalyses.StoresInheritedFieldTwoUp;
import io.ltr8.test.data.AccessorAnalyses.StoresInheritedPublicField;
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

	// --- the member that reads a constructor-bound field back: a public instance method, else a public field

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
				OverridesPublicFieldAccessor.class, InheritsHiddenField.class, InheritsHiddenTwoUp.class,
				InheritsAcrossHidden.class).map(type -> () -> {
					DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
					for (DataClassField field : record.fields()) {
						assertEquals(type, field.accessor().type().parameterType(0), type.getName() + " " + field.name());
					}
				}));
	}

	// --- a member of a super class that is not public: the public lookup reaches it through a public subclass

	@Test
	void aMemberOfASuperClassThatIsNotPublicIsReadThroughThePublicClassInheritingIt() {
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(InheritsHiddenField.class)),
				() -> assertEquals(List.of("v"), values(InheritsHiddenField.class, new InheritsHiddenField("v"))),
				() -> assertEquals(List.of("name:String"), fields(InheritsHiddenAccessor.class)),
				() -> assertEquals(List.of("v"), values(InheritsHiddenAccessor.class, new InheritsHiddenAccessor("v"))),
				() -> assertEquals(List.of("label:String"), fields(InheritsHiddenNamedAccessor.class)),
				() -> assertEquals(List.of("v"),
						values(InheritsHiddenNamedAccessor.class, new InheritsHiddenNamedAccessor("v"))),
				() -> assertEquals(List.of("name:String", "count:int"), fields(InheritsHiddenTwoUp.class)),
				() -> assertEquals(List.of("n", 2), values(InheritsHiddenTwoUp.class, new InheritsHiddenTwoUp("n", 2))),
				() -> assertEquals(List.of("name:String", "count:int", "label:String", "size:int"),
						fields(InheritsAcrossHidden.class)),
				() -> assertEquals(List.of("n", 2, "l", 3),
						values(InheritsAcrossHidden.class, new InheritsAcrossHidden("n", 2, "l", 3))));
	}

	@Test
	void classesInheritingMembersOfASuperClassThatIsNotPublicRoundTrip() {
		roundTrips(List.of(new InheritsHiddenField("v"), new InheritsHiddenAccessor("v"),
				new InheritsHiddenNamedAccessor("v"), new InheritsHiddenTwoUp("n", 2),
				new InheritsAcrossHidden("n", 2, "l", 3)));
	}

	/** The public lookup resolves the field's name from the public subclass, where it names another field. */
	@Test
	void aFieldOfASuperClassThatIsNotPublicHiddenFromThePublicSubclassIsRefusedNamingTheClassAndField() {
		Class<?> type = InheritsShadowedHiddenField.class;
		CodeAnalysisException refused = assertThrows(CodeAnalysisException.class, () -> context.getDescriptor(type));
		assertEquals(type.getName() + " is bound through field " + type.getSuperclass().getSuperclass().getName()
				+ ".name, which a field of " + type.getSuperclass().getName() + " hides from the public lookup",
				refused.getMessage());
		assertNull(refused.getCause());
	}

	/** Its own members, or failing those its constructor, are reached through it alone. */
	@Test
	void aClassThatIsNotPublicIsRefusedNamingTheClassAndMember() {
		assertAll(() -> {
			Class<?> type = fixture("HiddenDescribed");
			DataBindException refused = assertThrows(DataBindException.class, () -> context.getDescriptor(type));
			assertEquals(type.getName() + " is bound through a member that is not accessible: field " + type.getName()
					+ ".name, through no class the public lookup can access", refused.getMessage());
			assertInstanceOf(IllegalAccessException.class, refused.getCause(), type.getName());
		}, () -> {
			Class<?> type = fixture("HiddenInheritsPublicField");
			DataBindException refused = assertThrows(DataBindException.class, () -> context.getDescriptor(type));
			String message = refused.getMessage();
			assertTrue(message.startsWith(type.getName() + " is bound through a member that is not accessible: "
					+ "class is not public: " + type.getName() + ".<init>"), message);
			assertInstanceOf(IllegalAccessException.class, refused.getCause(), type.getName());
		});
	}

	// --- a public field that is not final the constructor binds: one component, read and written through the field

	@Test
	void aPublicFieldThatIsNotFinalTheConstructorBindsIsOneComponentReadAndWrittenThroughTheField() {
		PublicMutablePrimitives primitives = new PublicMutablePrimitives(3, 5_000_000_000L, 0.25, true);
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(PublicMutableReference.class)),
				() -> assertReadThroughField(field(PublicMutableReference.class, 0), "name"),
				() -> assertEquals(List.of("v"), values(PublicMutableReference.class, new PublicMutableReference("v"))),
				() -> assertEquals(List.of("count:int", "size:long", "ratio:double", "flag:boolean"),
						fields(PublicMutablePrimitives.class)),
				() -> assertReadThroughField(field(PublicMutablePrimitives.class, 1), "size"),
				() -> assertEquals(List.of(3, 5_000_000_000L, 0.25, true),
						values(PublicMutablePrimitives.class, primitives)),
				() -> assertEquals(List.of("items:List"), fields(PublicMutableList.class)),
				() -> assertEquals(List.of(List.of("a", "b")),
						values(PublicMutableList.class, new PublicMutableList(List.of("a", "b")))),
				() -> assertEquals(List.of("note:String"), fields(PublicMutableOptional.class)),
				() -> assertEquals(List.of("n"),
						values(PublicMutableOptional.class, new PublicMutableOptional(Optional.of("n")))),
				() -> assertEquals(List.of("label:String"), fields(NamedPublicMutableField.class)),
				() -> assertReadThroughField(field(NamedPublicMutableField.class, 0), "name"),
				() -> assertEquals(List.of("name:String", "count:int"), fields(InheritsPublicMutableField.class)),
				() -> assertEquals(List.of("n", 2),
						values(InheritsPublicMutableField.class, new InheritsPublicMutableField("n", 2))));
	}

	@Test
	void aPublicFieldThatIsNotFinalTheConstructorBindsKeepsTheFieldsSetter() {
		assertAll(Stream.of(PublicMutableReference.class, PublicMutablePrimitives.class, PublicMutableList.class,
				PublicMutableOptional.class, NamedPublicMutableField.class, InheritsPublicMutableField.class)
				.map(type -> () -> assertTrue(field(type, 0).setter().isPresent(), type.getName())));
		PublicMutableReference reference = new PublicMutableReference("v");
		InheritsPublicMutableField inherits = new InheritsPublicMutableField("n", 2);
		assertAll(
				() -> {
					field(PublicMutableReference.class, 0).set(reference, "w");
					assertEquals("w", reference.name);
				},
				() -> {
					field(InheritsPublicMutableField.class, 0).set(inherits, "m");
					assertEquals("m", inherits.name);
				});
	}

	@Test
	void aPublicFieldThatIsNotFinalRoundTripsOfEveryKind() {
		roundTrips(List.of(new PublicMutableReference("v"), new PublicMutablePrimitives(3, 5_000_000_000L, 0.25, true),
				new PublicMutableList(List.of("a", "b")), new PublicMutableList(List.of()),
				new PublicMutableOptional(Optional.of("n")), new PublicMutableOptional(Optional.empty()),
				new NamedPublicMutableField("v"), new InheritsPublicMutableField("n", 2)));
	}

	/** The field the constructor leaves is a component of the field alone, written after the constructor runs. */
	@Test
	void aPublicFieldThatIsNotFinalBesideOneTheConstructorLeavesIsAComponentApartFromIt() {
		BindsOnePublicMutableField noted = new BindsOnePublicMutableField("v");
		noted.note = "n";
		assertAll(
				() -> assertEquals(List.of("name:String", "note:String"), fields(BindsOnePublicMutableField.class)),
				() -> assertReadThroughField(field(BindsOnePublicMutableField.class, 0), "name"),
				() -> assertTrue(field(BindsOnePublicMutableField.class, 0).setter().isPresent()),
				() -> assertTrue(field(BindsOnePublicMutableField.class, 1).setter().isPresent()),
				() -> assertEquals(Arrays.asList("v", "n"), values(BindsOnePublicMutableField.class, noted)),
				() -> roundTrips(List.of(noted, new BindsOnePublicMutableField("v"))));
	}

	// --- a field a super class declares, which the subclass's own constructor stores

	@Test
	void anInheritedFieldTheConstructorStoresIsReadThroughTheAccessorReturningIt() {
		assertAll(Stream.of(new StoresInheritedField("v"), new StoresInheritedFieldTwoUp("v"),
				new StoresInheritedFieldReadBySubclass("v"), new StoresHiddenInheritedField("v")).map(value -> () -> {
					Class<?> type = value.getClass();
					assertEquals(List.of("name:String"), fields(type), type.getName());
					assertEquals(type, field(type, 0).accessor().type().parameterType(0), type.getName());
					assertEquals(List.of("v"), values(type, value), type.getName());
				}));
	}

	@Test
	void anInheritedPublicFieldTheConstructorStoresIsReadDirectly() {
		assertAll(Stream.of(new StoresInheritedPublicField("v"), new StoresHiddenInheritedPublicField("v"))
				.map(value -> () -> {
					Class<?> type = value.getClass();
					assertEquals(List.of("name:String"), fields(type), type.getName());
					assertEquals(type, field(type, 0).accessor().type().parameterType(0), type.getName());
					assertTrue(field(type, 0).setter().isPresent(), type.getName());
					assertEquals(List.of("v"), values(type, value), type.getName());
				}));
	}

	@Test
	void theAnnotationsOnAnInheritedFieldTheConstructorStoresApply() throws DataBindException {
		assertEquals(List.of("label:String", "tag:Object"), fields(StoresAnnotatedInheritedFields.class));
		DataClassUnion union = assertInstanceOf(DataClassUnion.class,
				field(StoresAnnotatedInheritedFields.class, 1).dataClass());
		assertEquals(List.of(String.class, Integer.class), List.of(union.memberTypes()));
		assertTrue(union.isSealed());
	}

	/** The field a store names is the one its owner reaches, not the described class's field of that name. */
	@Test
	void aStoreIntoASuperClassFieldThatASubclassFieldHidesBindsTheSuperClassField() {
		assertAll(
				() -> assertEquals(List.of("name:String"), fields(StoresHiddenSuperField.class)),
				() -> assertEquals(List.of("v"), values(StoresHiddenSuperField.class, new StoresHiddenSuperField("v"))),
				() -> assertEquals(List.of("name:String", "own:String"), fields(StoresBothFieldsOfOneName.class)),
				() -> assertEquals(List.of("b", "s"),
						values(StoresBothFieldsOfOneName.class, new StoresBothFieldsOfOneName("b", "s"))));
	}

	@Test
	void classesStoringInheritedFieldsRoundTrip() {
		roundTrips(List.of(new StoresInheritedField("v"), new StoresInheritedFieldTwoUp("v"),
				new StoresInheritedFieldReadBySubclass("v"), new StoresInheritedPublicField("v"),
				new StoresHiddenInheritedField("v"), new StoresHiddenInheritedPublicField("v"),
				new StoresAnnotatedInheritedFields("n", "x"), new StoresAnnotatedInheritedFields("n", 7),
				new StoresHiddenSuperField("v"), new StoresBothFieldsOfOneName("b", "s")));
	}

	// --- helpers

	/** A fixture class that is not public, so this package cannot name it. */
	private static Class<?> fixture(String simpleName) throws ClassNotFoundException {
		return Class.forName(AccessorAnalyses.class.getName() + "$" + simpleName);
	}

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
