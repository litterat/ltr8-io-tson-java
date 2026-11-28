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

import io.ltr8.annotation.Atom;
import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Tuple;
import io.ltr8.annotation.Unbound;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.bind.analysis.CodeAnalysisException;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.RecordRefusals;
import io.ltr8.test.data.RecordRefusals.CarrierNamedAlike;
import io.ltr8.test.data.RecordRefusals.ComponentsNamedAlike;
import io.ltr8.test.data.RecordRefusals.ComponentsNamedApart;
import io.ltr8.test.data.RecordRefusals.ConstructorComponentsOfOneName;
import io.ltr8.test.data.RecordRefusals.FieldBesidePair;
import io.ltr8.test.data.RecordRefusals.FieldBesidePairNamedApart;
import io.ltr8.test.data.RecordRefusals.PairShadowsBaseComponent;
import io.ltr8.test.data.RecordRefusals.PairShadowsBaseComponentNamedApart;
import io.ltr8.test.data.RecordRefusals.UnboundNamedAlike;
import io.ltr8.test.data.RecordRefusals.BridgeConstructorThrows;
import io.ltr8.test.data.RecordRefusals.BridgeInheritedOpen;
import io.ltr8.test.data.RecordRefusals.BridgeNeedsArgument;
import io.ltr8.test.data.RecordRefusals.BridgeNestedOpen;
import io.ltr8.test.data.RecordRefusals.BridgeObjectMismatch;
import io.ltr8.test.data.RecordRefusals.BridgeRaw;
import io.ltr8.test.data.RecordRefusals.BridgeRawlyExtended;
import io.ltr8.test.data.RecordRefusals.BridgeTypeVariable;
import io.ltr8.test.data.RecordRefusals.GenericBridge;
import io.ltr8.test.data.RecordRefusals.InheritedOpenBridge;
import io.ltr8.test.data.RecordRefusals.NestedOpenBridge;
import io.ltr8.test.data.RecordRefusals.RawBridge;
import io.ltr8.test.data.RecordRefusals.RawlyExtendedBridge;
import io.ltr8.test.data.RecordRefusals.BridgeWithoutNoArgumentConstructor;
import io.ltr8.test.data.RecordRefusals.ConstructorTransformsArgument;
import io.ltr8.test.data.RecordRefusals.Counted;
import io.ltr8.test.data.RecordRefusals.CountingBridge;
import io.ltr8.test.data.RecordRefusals.CycleMember;
import io.ltr8.test.data.RecordRefusals.CycleTop;
import io.ltr8.test.data.RecordRefusals.DesignatedParameterNarrower;
import io.ltr8.test.data.RecordRefusals.DesignatedParametersNameComponentTwice;
import io.ltr8.test.data.RecordRefusals.ProfileNamesComponentTwice;
import io.ltr8.test.data.RecordRefusals.ProfileParametersNameComponentTwice;
import io.ltr8.test.data.RecordRefusals.ProfileRenamesOntoAnother;
import io.ltr8.test.data.RecordRefusals.EmptyUnion;
import io.ltr8.test.data.RecordRefusals.FieldAnnotationWithoutAccessors;
import io.ltr8.test.data.RecordRefusals.GetterAndSetterRenamedApart;
import io.ltr8.test.data.RecordRefusals.Good;
import io.ltr8.test.data.RecordRefusals.GoodLoop;
import io.ltr8.test.data.RecordRefusals.HoldsBridgeNeedsArgument;
import io.ltr8.test.data.RecordRefusals.HoldsHolder;
import io.ltr8.test.data.RecordRefusals.HoldsRefused;
import io.ltr8.test.data.RecordRefusals.HoldsRefusedArray;
import io.ltr8.test.data.RecordRefusals.HoldsRefusedList;
import io.ltr8.test.data.RecordRefusals.HoldsRefusedMap;
import io.ltr8.test.data.RecordRefusals.HoldsRefusedOptional;
import io.ltr8.test.data.RecordRefusals.HoldsTwoCarriers;
import io.ltr8.test.data.RecordRefusals.HoldsTwoDeep;
import io.ltr8.test.data.RecordRefusals.HoldsUnconvertible;
import io.ltr8.test.data.RecordRefusals.LatticeLoop;
import io.ltr8.test.data.RecordRefusals.LatticeMid;
import io.ltr8.test.data.RecordRefusals.LatticeSide;
import io.ltr8.test.data.RecordRefusals.LatticeTop;
import io.ltr8.test.data.RecordRefusals.LatticeUp;
import io.ltr8.test.data.RecordRefusals.LoopWithRefused;
import io.ltr8.test.data.RecordRefusals.NoAccessorForArgument;
import io.ltr8.test.data.RecordRefusals.NamedUnionThroughCall;
import io.ltr8.test.data.RecordRefusals.NoDesignatedConstructor;
import io.ltr8.test.data.RecordRefusals.OnlyOtherProfiles;
import io.ltr8.test.data.RecordRefusals.OrderCountMismatch;
import io.ltr8.test.data.RecordRefusals.OrderedConstructorComponentsOfOneName;
import io.ltr8.test.data.RecordRefusals.OrderedFieldBesidePair;
import io.ltr8.test.data.RecordRefusals.OrderedPairShadowsBaseComponent;
import io.ltr8.test.data.RecordRefusals.OrderNamesComponentTwice;
import io.ltr8.test.data.RecordRefusals.OrderNamesNoField;
import io.ltr8.test.data.RecordRefusals.OrderNamesWireName;
import io.ltr8.test.data.RecordRefusals.OrderOmitsUnbound;
import io.ltr8.test.data.RecordRefusals.OrderedBridged;
import io.ltr8.test.data.RecordRefusals.OrderedBridgedNamingNothing;
import io.ltr8.test.data.RecordRefusals.OrderedTransparent;
import io.ltr8.test.data.RecordRefusals.OrderedTransparentNamingNothing;
import io.ltr8.test.data.RecordRefusals.OrderedTuple;
import io.ltr8.test.data.RecordRefusals.OrderedTupleNamingNothing;
import io.ltr8.test.data.RecordRefusals.PrivateAccessor;
import io.ltr8.test.data.RecordRefusals.ProfileFieldCountMismatch;
import io.ltr8.test.data.RecordRefusals.ProfileNamesNoComponent;
import io.ltr8.test.data.RecordRefusals.ProfileParameterNarrower;
import io.ltr8.test.data.RecordRefusals.ProfileParameterUnboxed;
import io.ltr8.test.data.RecordRefusals.ProfileParameterUnnamed;
import io.ltr8.test.data.RecordRefusals.ProfileParameterUnrelated;
import io.ltr8.test.data.RecordRefusals.RawList;
import io.ltr8.test.data.RecordRefusals.RawMap;
import io.ltr8.test.data.RecordRefusals.RefusedAfterCountedField;
import io.ltr8.test.data.RecordRefusals.RefusedLoopHoldingCounted;
import io.ltr8.test.data.RecordRefusals.RefusedLoopHoldingGoodLoop;
import io.ltr8.test.data.RecordRefusals.RingFirst;
import io.ltr8.test.data.RecordRefusals.RingLast;
import io.ltr8.test.data.RecordRefusals.RingMiddle;
import io.ltr8.test.data.RecordRefusals.TwoCarriers;
import io.ltr8.test.data.RecordRefusals.UnassignableUnion;
import io.ltr8.test.data.RecordRefusals.UnionAssignedUnchanged;
import io.ltr8.test.data.RecordRefusals.UnionThroughCall;
import io.ltr8.test.data.RecordRefusals.Unconvertible;
import io.ltr8.test.data.RecordRefusals.WildcardList;
import io.ltr8.test.data.ToDataBridges.Problem;
import io.ltr8.test.data.ToDataBridges.ProtectedConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Serial;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class the record binder cannot describe is refused with an exception whose own message says what is wrong
 * and names the class at fault, however deep in the type being described that class sits.
 */
class RecordRefusalTest {

	private static final String PROTECTED_CONSTRUCTOR = ProtectedConstructor.class.getName()
			+ " has no public constructor taking " + Problem.class.getName();

	private static final String NOT_ACCESSIBLE = " is bound through a member that is not accessible: ";

	private final DataBindContext context = DataBindContext.builder().build();

	private final DataBindContext profiled = DataBindContext.builder().profile("v1").build();

	// --- refused directly: the record binder

	@Test
	void aToDataRefusalIsTheMessageItself() {
		assertEquals(PROTECTED_CONSTRUCTOR, message(ProtectedConstructor.class));
	}

	@Test
	void twoAnnotationsCarriersAreRefusedNamingTheClass() {
		assertEquals("At most one component may have type io.ltr8.annotation.Annotations, the carrier for this "
				+ "value's own wire-format annotations -- found 2 on " + TwoCarriers.class, message(TwoCarriers.class));
	}

	@Test
	void aUnionWithNoMemberClassesIsRefused() {
		assertEquals("Union annotation must have one or more classes", message(EmptyUnion.class));
	}

	@Test
	void aUnionMemberTheComponentCannotHoldIsRefused() {
		assertEquals("Union types not assignable from class type", message(UnassignableUnion.class));
	}

	@Test
	void aFieldOrderOfTheWrongLengthIsRefusedNamingTheClass() {
		DataBindException refused = refusal(context, OrderCountMismatch.class);
		assertInstanceOf(CodeAnalysisException.class, refused);
		assertEquals(OrderCountMismatch.class.getName() + " has 2 components but its @FieldOrder names 1: it names "
				+ "each component once", refused.getMessage());
	}

	@Test
	void aFieldOrderLeavingOutAnUnboundComponentIsRefused() {
		assertEquals(OrderOmitsUnbound.class.getName() + " has 2 components but its @FieldOrder names 1: it names "
				+ "each component once", message(OrderOmitsUnbound.class));
	}

	@Test
	void aFieldOrderOverTwoComponentsOfOneJavaNameIsRefusedNamingTheClassAndTheName() {
		assertAll(Stream.of(OrderedFieldBesidePair.class, OrderedPairShadowsBaseComponent.class,
				OrderedConstructorComponentsOfOneName.class).map(type -> () -> {
					DataBindException refused = refusal(context, type);
					assertInstanceOf(CodeAnalysisException.class, refused, type.getName());
					assertEquals(type.getName() + " has two components named 'name', which @FieldOrder cannot tell "
							+ "apart: it names a component by its Java name", refused.getMessage());
					assertNull(refused.getCause());
				}));
	}

	@Test
	void aFieldOrderNamingNoComponentIsRefusedNamingTheClassAndTheName() {
		DataBindException refused = refusal(context, OrderNamesNoField.class);
		assertInstanceOf(CodeAnalysisException.class, refused);
		assertEquals(OrderNamesNoField.class.getName() + " has no component named 'c', which its @FieldOrder names: "
				+ "it names a component by its Java name", refused.getMessage());
	}

	@Test
	void aFieldOrderNamingAComponentByItsWireNameIsRefused() {
		assertEquals(OrderNamesWireName.class.getName() + " has no component named 'y', which its @FieldOrder names: "
				+ "it names a component by its Java name", message(OrderNamesWireName.class));
	}

	@Test
	void aFieldOrderNamingAComponentTwiceIsRefusedNamingTheClassAndTheName() {
		DataBindException refused = refusal(context, OrderNamesComponentTwice.class);
		assertInstanceOf(CodeAnalysisException.class, refused);
		assertEquals(OrderNamesComponentTwice.class.getName() + " has a @FieldOrder naming 'a' twice: it names each "
				+ "component once", refused.getMessage());
	}

	private static final String ORDER_DOES_NOT_APPLY = "an order applies only to a class described by its own "
			+ "components";

	@Test
	void aFieldOrderOnATupleIsRefusedNamingTheClass() {
		assertAll(Stream.of(OrderedTuple.class, OrderedTupleNamingNothing.class).map(type -> () -> {
			DataBindException refused = refusal(context, type);
			assertInstanceOf(CodeAnalysisException.class, refused, type.getName());
			assertEquals(type.getName() + " carries @FieldOrder, but is a @Tuple, whose elements are its record "
					+ "components in declared order; " + ORDER_DOES_NOT_APPLY, refused.getMessage());
			assertNull(refused.getCause());
		}));
	}

	@Test
	void aFieldOrderOnATransparentRecordIsRefusedNamingTheClass() {
		assertAll(Stream.of(OrderedTransparent.class, OrderedTransparentNamingNothing.class).map(type -> () -> {
			DataBindException refused = refusal(context, type);
			assertEquals(type.getName() + " carries @FieldOrder, but is @Transparent, written as its one component; "
					+ ORDER_DOES_NOT_APPLY, refused.getMessage());
			assertNull(refused.getCause());
		}));
	}

	@Test
	void aFieldOrderOnAClassBridgedThroughToDataIsRefusedNamingTheClassAndItsRecord() {
		assertAll(Stream.of(OrderedBridged.class, OrderedBridgedNamingNothing.class).map(type -> () -> {
			DataBindException refused = refusal(context, type);
			assertEquals(type.getName() + " carries @FieldOrder, but is bridged through ToData to "
					+ Good.class.getName() + ", which describes it; " + ORDER_DOES_NOT_APPLY + ", so it belongs on "
					+ Good.class.getName(), refused.getMessage());
			assertNull(refused.getCause());
		}));
	}

	@Test
	void aClassWithNoDesignatedConstructorIsRefusedNamingTheClass() {
		assertEquals("Could not find constructor: " + NoDesignatedConstructor.class,
				message(NoDesignatedConstructor.class));
	}

	@Test
	void aProfileNoConstructorServesIsRefusedNamingTheClass() {
		assertAll(
				() -> assertEquals("No constructor for profile 'v1' on " + NoDesignatedConstructor.class.getName()
						+ " -- it declares no @Profile constructors, and none is designated with @Record",
						message(profiled, NoDesignatedConstructor.class)),
				() -> assertEquals("No constructor for profile 'v2' on " + OnlyOtherProfiles.class.getName()
						+ ", which offers [v1] -- and none is designated with @Record as the fallback for profiles "
						+ "it does not name",
						message(DataBindContext.builder().profile("v2").build(), OnlyOtherProfiles.class)));
	}

	@Test
	void aFieldBridgeLeavingATypeArgumentOpenIsRefusedNamingTheBridgeAndTheField() {
		assertAll(
				() -> assertBridgeRefusal(BridgeTypeVariable.class, GenericBridge.class, "implements "
						+ "DataBridge<T, java.lang.String>, leaving its data type open"),
				() -> assertBridgeRefusal(BridgeInheritedOpen.class, InheritedOpenBridge.class, "implements "
						+ "DataBridge<java.lang.String, O>, leaving its object type open"),
				() -> assertBridgeRefusal(BridgeNestedOpen.class, NestedOpenBridge.class, "implements "
						+ "DataBridge<java.util.List<T>, java.lang.String>, leaving its data type open"));
	}

	@Test
	void aRawFieldBridgeIsRefusedNamingTheBridgeAndTheField() {
		assertAll(
				() -> assertBridgeRefusal(BridgeRaw.class, RawBridge.class, "reaches DataBridge raw"),
				() -> assertBridgeRefusal(BridgeRawlyExtended.class, RawlyExtendedBridge.class,
						"reaches DataBridge raw"));
	}

	private void assertBridgeRefusal(Class<?> holder, Class<?> bridge, String fault) {
		DataBindException refused = refusal(context, holder);
		assertInstanceOf(CodeAnalysisException.class, refused);
		assertEquals(bridge.getName() + ", the @Field bridge of " + holder.getName() + "'s field 'value', " + fault
				+ ": a field bridge names the classes it converts between, DataBridge<D, O>", refused.getMessage());
	}

	@Test
	void aFieldBridgeForAnotherTypeIsRefused() {
		assertEquals("Bridge object type not assignable from field type", message(BridgeObjectMismatch.class));
	}

	@Test
	void aFieldBridgeThatCannotBeBuiltIsRefused() {
		DataBindException refused = refusal(context, BridgeConstructorThrows.class);
		assertEquals("Failed to instantiate bridge", refused.getMessage());
		assertEquals("this bridge will not be built", refused.getCause().getCause().getMessage());
	}

	// --- refused directly: a component finder

	@Test
	void aProfileListingTheWrongNumberOfFieldsIsRefusedNamingTheClass() {
		assertEquals("@Profile on '" + ProfileFieldCountMismatch.class.getName() + "' lists 2 field name(s) for a "
				+ "constructor taking 1 parameter(s)", message(profiled, ProfileFieldCountMismatch.class));
	}

	@Test
	void aProfileNamingNoComponentIsRefusedNamingTheClass() {
		assertEquals("'" + ProfileNamesNoComponent.class.getName() + "' names no component 'z', so the selected "
				+ "constructor's parameter has no accessor to read it back through",
				message(profiled, ProfileNamesNoComponent.class));
	}

	@Test
	void aProfileNamingOneComponentTwiceIsRefusedNamingTheClassTheProfileAndTheComponent() {
		String twice = " both name the component 'a'; a constructor takes each component once";
		assertAll(
				() -> assertEquals("Parameters 0 and 1 of the constructor @Profile [v1] selects on '"
						+ ProfileNamesComponentTwice.class.getName() + "'" + twice,
						message(profiled, ProfileNamesComponentTwice.class)),
				() -> assertEquals("Parameters 0 and 1 of the constructor @Profile [v1] selects on '"
						+ ProfileParametersNameComponentTwice.class.getName() + "'" + twice,
						message(profiled, ProfileParametersNameComponentTwice.class)));
	}

	@Test
	void aDesignatedConstructorNamingOneComponentTwiceIsRefusedNamingTheClassAndTheComponent() {
		assertEquals("Parameters 0 and 1 of the selected constructor on '"
				+ DesignatedParametersNameComponentTwice.class.getName() + "' both name the component 'a'; a "
				+ "constructor takes each component once", message(DesignatedParametersNameComponentTwice.class));
	}

	@Test
	void aProfiledParameterRenamedOntoAnotherComponentsNameIsRefusedAsTwoComponentsOfOneName() {
		assertEquals(sharedName(ProfileRenamesOntoAnother.class, "a"),
				message(profiled, ProfileRenamesOntoAnother.class));
	}

	@Test
	void aProfiledParameterWithNoNameIsRefusedNamingTheClass() {
		assertEquals("Cannot name parameter 0 of the selected constructor on '"
				+ ProfileParameterUnnamed.class.getName() + "': a secondary constructor keeps no parameter names "
				+ "unless the class was compiled with -parameters. List them with @Profile(fields = {...}) or name "
				+ "this one with @Field", message(profiled, ProfileParameterUnnamed.class));
	}

	@Test
	void aSelectedConstructorParameterOfAnotherTypeThanItsComponentIsRefusedNamingTheClass() {
		assertAll(
				() -> assertParameterTypeRefusal(profiled, ProfileParameterNarrower.class, "int", "count", "long"),
				() -> assertParameterTypeRefusal(profiled, ProfileParameterUnboxed.class, "int", "count",
						"java.lang.Integer"),
				() -> assertParameterTypeRefusal(profiled, ProfileParameterUnrelated.class, "java.lang.Integer",
						"label", "java.lang.String"),
				() -> assertParameterTypeRefusal(context, DesignatedParameterNarrower.class, "int", "count",
						"long"));
	}

	private static void assertParameterTypeRefusal(DataBindContext context, Class<?> type, String parameterType,
			String component, String componentType) {
		DataBindException refused = refusal(context, type);
		assertInstanceOf(CodeAnalysisException.class, refused);
		assertEquals("Parameter 0 of the selected constructor on '" + type.getName() + "' is " + parameterType
				+ ", but the component '" + component + "' it names is " + componentType + ": a constructor "
				+ "parameter takes its component's type", refused.getMessage());
	}

	@Test
	void aConstructorThatTransformsItsArgumentIsRefusedNamingTheClass() {
		assertEquals("Failed to match immutable fields for class: " + ConstructorTransformsArgument.class
				+ ". Add @Field annotations to assist.", message(ConstructorTransformsArgument.class));
	}

	@Test
	void aUnionParameterNotAssignedUnchangedAndNotNamedIsRefusedNamingTheClassAndParameter() {
		DataBindException refused = refusal(context, UnionThroughCall.class);
		assertInstanceOf(CodeAnalysisException.class, refused);
		assertEquals(UnionThroughCall.class.getName() + "'s constructor does not assign its @Union parameter 0 "
				+ "unchanged to a field, and no @Field names it", refused.getMessage());
		assertNull(refused.getCause());
	}

	@Test
	void aUnionParameterNamedByFieldOrAssignedUnchangedIsDescribed() {
		assertAll(Stream.of(new NamedUnionThroughCall("named"), new UnionAssignedUnchanged(7)).map(value -> () -> {
			DataClassRecord record = assertInstanceOf(DataClassRecord.class,
					context.getDescriptor(value.getClass()));
			assertEquals(1, record.fields().length);
			DataClassField tag = record.fields()[0];
			assertEquals("tag", tag.name());
			assertInstanceOf(DataClassUnion.class, tag.dataClass());
			MapMapper mapper = new MapMapper(context);
			assertEquals(tag.get(value), tag.get(mapper.toObject(value.getClass(), mapper.toMap(value))));
		}));
	}

	@Test
	void anArgumentWithNoAccessorIsRefusedNamingTheClassAndField() {
		assertEquals("Failed to match immutable field accessor for class: " + NoAccessorForArgument.class
				+ ". Add @Field annotations to assist field 'name'", message(NoAccessorForArgument.class));
	}

	@Test
	void anArgumentWhoseOnlyAccessorIsPrivateIsRefusedNamingTheClassAndField() {
		DataBindException refused = refusal(context, PrivateAccessor.class);
		assertEquals("Failed to match immutable field accessor for class: " + PrivateAccessor.class
				+ ". Add @Field annotations to assist field 'name'", refused.getMessage());
		assertNull(refused.getCause());
	}

	@Test
	void aRecordComponentThePublicLookupCannotReadIsRefusedNamingTheClassAndComponent() {
		String record = HiddenRecord.class.getName();
		assertRefusedAlike(DataBindContext::builder, HiddenRecord.class,
				"Failed to access accessor for record component 'text' on '" + record + "'",
				unreachable(HiddenRecord.class, "method " + record + ".text"),
				"symbolic reference class is not accessible: class " + record + ", from public Lookup");
	}

	@Test
	void aRecordComponentOfAProfileThePublicLookupCannotReadIsRefusedNamingTheClassAndComponent() {
		String record = HiddenProfiledRecord.class.getName();
		assertRefusedAlike(() -> DataBindContext.builder().profile("v1"), HiddenProfiledRecord.class,
				"Failed to access accessor for record component 'text' on '" + record + "'",
				unreachable(HiddenProfiledRecord.class, "method " + record + ".text"),
				"symbolic reference class is not accessible: class " + record + ", from public Lookup");
	}

	@Test
	void aFieldAnnotationWithNoAccessorsIsRefusedNamingTheField() {
		assertEquals("Could not find matching getter/setter for field 'hidden'",
				message(FieldAnnotationWithoutAccessors.class));
	}

	@Test
	void aGetterAndSetterRenamedApartAreRefusedNamingTheField() {
		assertEquals("Field 'value' already has Field annotation", message(GetterAndSetterRenamedApart.class));
	}

	// --- two components of one wire name

	private static String sharedName(Class<?> type, String name) {
		return type.getName() + " binds two components to the name '" + name + "'; @Field on one of them names it "
				+ "apart";
	}

	@Test
	void aPublicFieldBesideAPairOfItsNameIsRefusedNamingTheClassAndTheName() {
		DataBindException refused = refusal(context, FieldBesidePair.class);
		assertInstanceOf(CodeAnalysisException.class, refused);
		assertEquals(sharedName(FieldBesidePair.class, "name"), refused.getMessage());
		assertNull(refused.getCause());
	}

	@Test
	void aPairTakingTheNameOfASuperClassConstructorComponentIsRefusedNamingTheClassAndTheName() {
		DataBindException refused = refusal(context, PairShadowsBaseComponent.class);
		assertInstanceOf(CodeAnalysisException.class, refused);
		assertEquals(sharedName(PairShadowsBaseComponent.class, "name"), refused.getMessage());
	}

	@Test
	void recordComponentsGivenOneNameByFieldAreRefusedNamingTheClassAndTheName() {
		assertEquals(sharedName(ComponentsNamedAlike.class, "x"), message(ComponentsNamedAlike.class));
	}

	/** {@code RecordBindReader} in {@code tson-compiler} tells an unbound component by its name. */
	@Test
	void anUnboundComponentSharingABoundComponentsNameIsRefused() {
		assertEquals(sharedName(UnboundNamedAlike.class, "x"), message(UnboundNamedAlike.class));
	}

	@Test
	void twoConstructorComponentsOfOneNameKeepTheConstructorAnalysisRefusal() {
		assertEquals(ConstructorComponentsOfOneName.class.getName() + " has two fields named 'name'; @Field on an "
				+ "accessor names one apart", message(ConstructorComponentsOfOneName.class));
	}

	/** The carrier is no field of the wire: it is filled from the value's own annotations, never by name. */
	@Test
	void theAnnotationsCarrierMayShareAFieldsName() throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class,
				context.getDescriptor(CarrierNamedAlike.class));
		assertEquals(List.of("annotations", "annotations"), Stream.of(record.fields()).map(DataClassField::name)
				.toList());
		assertEquals(Map.of("annotations", 0), record.fieldIndex());
	}

	@Test
	void theSameShapesNamedApartAreDescribedAndRoundTripThroughBothMappers() {
		assertAll(
				() -> assertNames(FieldBesidePairNamedApart.class, "alias", "name"),
				() -> assertNames(PairShadowsBaseComponentNamedApart.class, "name", "own"),
				() -> assertNames(ComponentsNamedApart.class, "x", "y"),
				() -> assertRoundTrips(new FieldBesidePairNamedApart("field", "pair")),
				() -> assertRoundTrips(new PairShadowsBaseComponentNamedApart("base", "pair")),
				() -> assertRoundTrips(new ComponentsNamedApart("a", "b")));
	}

	private void assertNames(Class<?> type, String... names) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		assertEquals(List.of(names), Stream.of(record.fields()).map(DataClassField::name).toList());
	}

	private void assertRoundTrips(Object value) throws Throwable {
		MapMapper maps = new MapMapper(context);
		ArrayMapper arrays = new ArrayMapper(context);
		assertEquals(value, maps.toObject(value.getClass(), maps.toMap(value)), "map");
		assertEquals(value, arrays.toObject(value.getClass(), arrays.toArray(value)), "array");
	}

	// --- refused directly: the reflective failures resolveRecord turns into a refusal

	@Test
	void aClassThePublicLookupCannotReachIsRefusedNamingTheClassWithTheCauseKept() {
		DataBindException refused = refusal(context, HiddenEmpty.class);
		assertEquals(HiddenEmpty.class.getName() + NOT_ACCESSIBLE + "constructor " + HiddenEmpty.class.getName()
				+ "(), of a class the public lookup cannot access", refused.getMessage());
		assertInstanceOf(IllegalAccessException.class, refused.getCause());
	}

	@Test
	void aConstructorThePublicLookupCannotReachIsNamedWithItsParameterTypes() {
		DataBindException refused = refusal(context, HiddenTakingArguments.class);
		assertEquals(HiddenTakingArguments.class.getName() + NOT_ACCESSIBLE + "constructor "
				+ HiddenTakingArguments.class.getName() + "(java.lang.String, int, java.lang.String[]), of a class "
				+ "the public lookup cannot access", refused.getMessage());
		assertInstanceOf(IllegalAccessException.class, refused.getCause());
	}

	@Test
	void aFieldBridgeThePublicLookupCannotReachIsRefusedNamingBothClassesWithTheCauseKept() {
		DataBindException refused = refusal(context, UsesHiddenBridge.class);
		assertEquals(UsesHiddenBridge.class.getName() + NOT_ACCESSIBLE + HiddenBridge.class.getName()
				+ ", the @Field bridge of " + UsesHiddenBridge.class.getName() + "'s field 'value', is a class the "
				+ "public lookup cannot access", refused.getMessage());
		assertInstanceOf(IllegalAccessException.class, refused.getCause());
	}

	@Test
	void aRefusalOfWhatThePublicLookupCannotReachReadsTheSameInEveryContext() {
		DataBindContext named = DataBindContext.builder()
				.nameBinder(DataNameBinder.ofMap(Map.of("hidden", HiddenEmpty.class))).build();
		DataBindContext again = DataBindContext.builder()
				.nameBinder(DataNameBinder.ofMap(Map.of("hidden", HiddenEmpty.class))).build();
		DataBindContext fresh = DataBindContext.builder().build();
		assertAll(
				() -> assertEquals(message(context, HiddenEmpty.class), message(fresh, HiddenEmpty.class)),
				() -> assertEquals(message(context, HiddenTakingArguments.class),
						message(fresh, HiddenTakingArguments.class)),
				() -> assertEquals(message(context, UsesHiddenBridge.class), message(fresh, UsesHiddenBridge.class)),
				() -> assertEquals(namedRefusal(named, "hidden"), namedRefusal(again, "hidden")));
	}

	@Test
	void anAtomConstructorThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String atom = HiddenAtom.class.getName();
		assertRefusedAlike(DataBindContext::builder, HiddenAtom.class, "Failed to get atom descriptor",
				atom + NOT_ACCESSIBLE + "constructor " + atom + "(java.lang.String), of a class the public lookup "
						+ "cannot access",
				"symbolic reference class is not accessible: class " + atom + ", from public Lookup");
	}

	@Test
	void anAtomsToDataThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String atom = AtomWithHiddenToData.class.getName();
		assertRefusedAlike(DataBindContext::builder, AtomWithHiddenToData.class, "Failed to get atom descriptor",
				atom + NOT_ACCESSIBLE + "method " + atom + ".toData, which is not public");
	}

	@Test
	void anAtomFactoryThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String atom = HiddenAtomFactory.class.getName();
		assertRefusedAlike(DataBindContext::builder, HiddenAtomFactory.class, "Failed to get atom descriptor",
				unreachable(HiddenAtomFactory.class, "method " + atom + ".of"),
				"symbolic reference class is not accessible: class " + atom + ", from public Lookup");
	}

	@Test
	void anAtomAccessorThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String atom = AtomFactoryWithHiddenAccessor.class.getName();
		assertRefusedAlike(DataBindContext::builder, AtomFactoryWithHiddenAccessor.class,
				"Failed to get atom descriptor",
				atom + NOT_ACCESSIBLE + "method " + atom + ".code, which is not public");
	}

	/** A tuple is refused where a record of its visibility is ({@link HiddenRecord}), and alike. */
	@Test
	void aTupleThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String tuple = HiddenTuple.class.getName();
		assertRefusedAlike(DataBindContext::builder, HiddenTuple.class,
				"Failed to get tuple descriptor for " + HiddenTuple.class,
				unreachable(HiddenTuple.class, "method " + tuple + ".text"),
				"symbolic reference class is not accessible: class " + tuple + ", from public Lookup");
	}

	@Test
	void aTransparentRecordThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String record = HiddenTransparent.class.getName();
		assertRefusedAlike(DataBindContext::builder, HiddenTransparent.class,
				"@Transparent record " + record + " needs a public canonical constructor taking its one component "
						+ "(java.lang.String)",
				unreachable(HiddenTransparent.class, "method " + record + ".value"),
				"symbolic reference class is not accessible: class " + record + ", from public Lookup");
	}

	@Test
	void aToDataClassThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String bridged = HiddenToData.class.getName();
		assertRefusedAlike(DataBindContext::builder, HiddenToData.class,
				bridged + " is not accessible through the public lookup: a bridged class is public, in a package its "
						+ "module exports",
				bridged + NOT_ACCESSIBLE + "constructor " + bridged + "(" + Wrapped.class.getName() + "), of a class "
						+ "the public lookup cannot access",
				"symbolic reference class is not accessible: class " + bridged + ", from public Lookup");
	}

	@Test
	void aCollectionClassThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String list = HiddenList.class.getName();
		assertRefusedAlike(DataBindContext::builder, HoldsHiddenList.class,
				list + NOT_ACCESSIBLE + "constructor " + list + "(int), of a class the public lookup cannot access",
				"symbolic reference class is not accessible: class " + list + ", from public Lookup");
	}

	@Test
	void aMapClassThePublicLookupCannotReachIsRefusedAlikeInEveryContext() {
		String map = HiddenMap.class.getName();
		assertRefusedAlike(DataBindContext::builder, HoldsHiddenMap.class,
				map + NOT_ACCESSIBLE + "constructor " + map + "(int), of a class the public lookup cannot access",
				"symbolic reference class is not accessible: class " + map + ", from public Lookup");
	}

	private static String unreachable(Class<?> described, String member) {
		return described.getName() + NOT_ACCESSIBLE + member + ", through no class the public lookup can access";
	}

	/**
	 * Directly and by name, each in two fresh contexts: {@code chain} is the refusal's message and its causes',
	 * outermost first, and naming nothing by identity they read the same in each.
	 */
	private static void assertRefusedAlike(Supplier<DataBindContext.Builder> builder, Class<?> type,
			String... chain) {
		String named = "'refused' binds " + type.getName() + ", which cannot be analysed: " + String.join(": ", chain);
		assertAll(Stream.of(1, 2).flatMap(run -> Stream.<Executable>of(
				() -> assertEquals(List.of(chain), messages(refusal(builder.get().build(), type))),
				() -> assertEquals(named, namedRefusal(builder.get()
						.nameBinder(DataNameBinder.ofMap(Map.of("refused", type))).build(), "refused")))));
	}

	private static List<String> messages(Throwable refused) {
		List<String> messages = new ArrayList<>();
		for (Throwable at = refused; at != null; at = at.getCause()) {
			messages.add(at.getMessage());
		}
		return messages;
	}

	@Test
	void aFieldBridgeWithoutANoArgumentConstructorIsRefusedNamingBothClassesWithTheCauseKept() {
		DataBindException refused = refusal(context, BridgeNeedsArgument.class);
		assertEquals(BridgeNeedsArgument.class.getName() + " is bound through "
				+ BridgeWithoutNoArgumentConstructor.class.getName() + ".<init>(), which is not a public constructor "
				+ "or method", refused.getMessage());
		assertInstanceOf(NoSuchMethodException.class, refused.getCause());
	}

	// --- a good record holding a refused type: the top message is the innermost fact

	@Test
	void aRefusedFieldTypeIsTheMessage() {
		assertEquals(PROTECTED_CONSTRUCTOR, message(HoldsRefused.class));
	}

	@Test
	void aRefusedListElementIsTheMessage() {
		assertEquals(PROTECTED_CONSTRUCTOR, message(HoldsRefusedList.class));
	}

	@Test
	void aRefusedOptionalValueIsTheMessage() {
		assertEquals(PROTECTED_CONSTRUCTOR, message(HoldsRefusedOptional.class));
	}

	@Test
	void aRefusedMapValueIsTheMessage() {
		assertEquals(PROTECTED_CONSTRUCTOR, message(HoldsRefusedMap.class));
	}

	@Test
	void aRefusedArrayElementIsTheMessage() {
		assertEquals(PROTECTED_CONSTRUCTOR, message(HoldsRefusedArray.class));
	}

	@Test
	void aRefusedTupleElementIsTheMessage() {
		assertEquals(message(OrderCountMismatch.class), message(TupleHoldsRefused.class));
	}

	@Test
	void aRefusedTypeTwoRecordsDeepIsTheMessage() {
		assertAll(
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(HoldsHolder.class)),
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(HoldsTwoDeep.class)));
	}

	@Test
	void aRefusedFieldTypeOutsideToDataIsTheMessage() {
		assertAll(
				() -> assertEquals(message(TwoCarriers.class), message(HoldsTwoCarriers.class)),
				() -> assertEquals(message(BridgeNeedsArgument.class), message(HoldsBridgeNeedsArgument.class)),
				() -> assertEquals("Unable to find a valid data conversion for class: " + Unconvertible.class,
						message(HoldsUnconvertible.class)));
	}

	@Test
	void aCollectionOrMapComponentWithoutUsableTypeArgumentsIsTheMessage() {
		assertAll(
				() -> assertEquals("Collection must provide parameterized type information", message(RawList.class)),
				() -> assertEquals("Unrecognized parameterized type", message(WildcardList.class)),
				() -> assertEquals("Map must provide parameterized type information", message(RawMap.class)));
	}

	@Test
	void noRefusalCarriesTheGenericWrapperAnywhereOnItsCauseChain() {
		List<Class<?>> refused = List.of(ProtectedConstructor.class, TwoCarriers.class, EmptyUnion.class,
				UnassignableUnion.class, OrderCountMismatch.class, OrderNamesNoField.class, OrderNamesComponentTwice.class,
				NoDesignatedConstructor.class, BridgeTypeVariable.class, BridgeInheritedOpen.class,
				BridgeNestedOpen.class, BridgeRaw.class, BridgeRawlyExtended.class, BridgeObjectMismatch.class,
				BridgeConstructorThrows.class, BridgeNeedsArgument.class, ConstructorTransformsArgument.class,
				NoAccessorForArgument.class, PrivateAccessor.class, HiddenRecord.class,
				FieldAnnotationWithoutAccessors.class, GetterAndSetterRenamedApart.class, HiddenEmpty.class,
				UsesHiddenBridge.class, HoldsRefused.class, HoldsRefusedList.class,
				HoldsRefusedOptional.class, HoldsRefusedMap.class, HoldsRefusedArray.class, HoldsHolder.class,
				HoldsTwoDeep.class, HoldsTwoCarriers.class, HoldsBridgeNeedsArgument.class,
				HoldsUnconvertible.class, RawList.class, WildcardList.class, RawMap.class, CycleTop.class,
				LoopWithRefused.class, UnionThroughCall.class, DesignatedParameterNarrower.class,
				FieldBesidePair.class, PairShadowsBaseComponent.class, ComponentsNamedAlike.class,
				UnboundNamedAlike.class, ConstructorComponentsOfOneName.class,
				DesignatedParametersNameComponentTwice.class);
		List<Class<?>> refusedUnderProfile = List.of(ProfileFieldCountMismatch.class,
				ProfileNamesNoComponent.class, ProfileParameterUnnamed.class, ProfileParameterNarrower.class,
				ProfileParameterUnboxed.class, ProfileParameterUnrelated.class, ProfileNamesComponentTwice.class,
				ProfileParametersNameComponentTwice.class, ProfileRenamesOntoAnother.class);
		assertAll(Stream.concat(
				refused.stream().map(type -> () -> assertNoGenericWrapper(context, type)),
				refusedUnderProfile.stream().map(type -> () -> assertNoGenericWrapper(profiled, type))));
	}

	// --- by name

	@Test
	void aNameBoundToARefusedClassStatesTheFactOnce() {
		DataBindContext named = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(Map.of(
				"holder", HoldsRefused.class, "bridged", BridgeNeedsArgument.class, "hidden", HiddenEmpty.class)))
				.build();
		assertAll(
				() -> assertNamedRefusal(named, "holder", HoldsRefused.class, PROTECTED_CONSTRUCTOR),
				() -> assertNamedRefusal(named, "bridged", BridgeNeedsArgument.class,
						message(BridgeNeedsArgument.class)),
				() -> assertNamedRefusal(named, "hidden", HiddenEmpty.class, reasons(refusal(context, HiddenEmpty.class))));
	}

	/** A refusal's message, then its cause's: the name binder states each distinct message on the chain. */
	private static String reasons(DataBindException refused) {
		return refused.getMessage() + ": " + refused.getCause().getMessage();
	}

	private static void assertNamedRefusal(DataBindContext named, String name, Class<?> bound, String fact) {
		String message = namedRefusal(named, name);
		assertEquals("'" + name + "' binds " + bound.getName() + ", which cannot be analysed: " + fact, message);
		assertOnce(message, fact);
	}

	private static String namedRefusal(DataBindContext named, String name) {
		return assertThrows(DataBindException.class, () -> named.getDescriptor(name)).getMessage();
	}

	private static void assertOnce(String message, String part) {
		assertEquals(message.indexOf(part), message.lastIndexOf(part), message);
	}

	// --- a refusal does not disturb the context

	@Test
	void aRefusalIsRepeatableAndLeavesTheContextDescribingGoodClasses() throws DataBindException {
		String first = message(HoldsRefused.class);
		assertEquals(first, message(HoldsRefused.class));
		assertEquals(PROTECTED_CONSTRUCTOR, message(ProtectedConstructor.class));
		String reflective = message(BridgeNeedsArgument.class);
		assertEquals(reflective, message(BridgeNeedsArgument.class));

		DataClassRecord good = assertInstanceOf(DataClassRecord.class, context.getDescriptor(Good.class));
		assertEquals(2, good.fields().length);
		assertEquals(first, message(HoldsRefused.class));
	}

	// --- a cyclic type graph with a refused member

	@Test
	void aCycleHoldingARefusedTypeIsRefusedWithItsFact() {
		assertAll(
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(LoopWithRefused.class)),
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(LoopWithRefused.class)),
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(CycleTop.class)),
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(CycleTop.class)));
	}

	@Test
	void aCycleMemberReachingARefusedTypeIsRefusedWhenAskedForFirst() {
		DataBindContext fresh = DataBindContext.builder().build();
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, CycleMember.class));
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, CycleTop.class));
	}

	@Test
	void aCycleMemberReachingARefusedTypeIsRefusedWhenAskedForAfterIt() {
		DataBindContext fresh = DataBindContext.builder().build();
		assertAll(
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, CycleTop.class)),
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, CycleMember.class)),
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, CycleTop.class)),
				() -> assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, CycleMember.class)));
	}

	@Test
	void everyClassOfARingRefusedInTheMiddleIsRefusedWhicheverIsAskedFirst() {
		List<Class<?>> ring = List.of(RingFirst.class, RingMiddle.class, RingLast.class);
		assertEveryOrderRefused(ring);
	}

	/**
	 * {@code LatticeSide} meets {@code LatticeLoop} after {@code LatticeMid}, which {@code LatticeLoop} closes on,
	 * has been described: {@code LatticeLoop} then waits on {@code LatticeTop}, not on {@code LatticeMid} alone.
	 */
	@Test
	void aClassHoldingAMemberOfAnUnfinishedNestedCycleIsRefusedWithIt() {
		assertEveryOrderRefused(List.of(LatticeTop.class, LatticeMid.class, LatticeLoop.class, LatticeUp.class,
				LatticeSide.class));
	}

	/** Each class asked first of a fresh context, then the rest of them, in the order given. */
	private static void assertEveryOrderRefused(List<Class<?>> cycle) {
		assertAll(cycle.stream().map(first -> () -> {
			DataBindContext fresh = DataBindContext.builder().build();
			assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, first), first.getName());
			for (Class<?> then : cycle) {
				assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, then), first.getName() + ", then " + then.getName());
			}
		}));
	}

	@Test
	void aSelfReferencingRefusedClassIsRefusedAgainAndLeavesAGoodLoopDescribed() throws DataBindException {
		DataBindContext fresh = DataBindContext.builder().build();
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, LoopWithRefused.class));
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, LoopWithRefused.class));
		assertGoodLoop(fresh);
	}

	@Test
	void aGoodCycleDescribedDuringARefusalStaysCachedAndWhole() throws DataBindException {
		int before = CountingBridge.BUILT.get();
		DataBindContext.builder().build().getDescriptor(Counted.class);
		int describedOnce = CountingBridge.BUILT.get();
		assertTrue(describedOnce > before);

		DataBindContext fresh = DataBindContext.builder().build();
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, RefusedLoopHoldingCounted.class));
		int described = describedOnce - before;
		assertEquals(describedOnce + described, CountingBridge.BUILT.get(), "Counted is described on the way");

		DataClassRecord counted = assertInstanceOf(DataClassRecord.class, fresh.getDescriptor(Counted.class));
		assertEquals(describedOnce + described, CountingBridge.BUILT.get(), "and kept: asking for it reuses it");
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, RefusedLoopHoldingCounted.class));
		assertEquals(describedOnce + described, CountingBridge.BUILT.get(), "as does asking for the refused class");
		assertSame(counted, fresh.getDescriptor(Counted.class));

		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, RefusedLoopHoldingGoodLoop.class));
		assertGoodLoop(fresh);
	}

	@Test
	void aRefusalIsNotCachedButWorkedOutAgainEachTimeItIsAsked() {
		DataBindContext fresh = DataBindContext.builder().build();
		int before = CountingBridge.BUILT.get();
		String first = message(fresh, RefusedAfterCountedField.class);
		assertEquals(before + 1, CountingBridge.BUILT.get());
		assertEquals(first, message(fresh, RefusedAfterCountedField.class));
		assertEquals(before + 2, CountingBridge.BUILT.get());
	}

	@Test
	void aDescriptorCachedBeforeARefusalIsTheSameInstanceAfterIt() throws DataBindException {
		DataBindContext fresh = DataBindContext.builder().build();
		DataClassRecord loop = assertGoodLoop(fresh);
		DataClassRecord good = assertInstanceOf(DataClassRecord.class, fresh.getDescriptor(Good.class));

		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, RefusedLoopHoldingGoodLoop.class));
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, CycleTop.class));
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, CycleMember.class));

		assertSame(loop, fresh.getDescriptor(GoodLoop.class));
		assertSame(loop, next(loop).dataClass());
		assertSame(good, fresh.getDescriptor(Good.class));
	}

	@Test
	void aGoodClassAskedForFirstAfterARefusalIsDescribed() throws DataBindException {
		DataBindContext fresh = DataBindContext.builder().build();
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, CycleTop.class));
		assertEquals(PROTECTED_CONSTRUCTOR, message(fresh, LatticeTop.class));
		assertEquals(2, assertInstanceOf(DataClassRecord.class, fresh.getDescriptor(Good.class)).fields().length);
		assertGoodLoop(fresh);
	}

	/**
	 * One context asked from several threads at once, each walking the classes in its own order: every refusal is
	 * the same fact, and every good descriptor any thread is handed can be walked across its cyclic edge.
	 */
	@Test
	void threadsAskingOneContextAtOnceAllSeeTheSameAnswers() throws Exception {
		List<Class<?>> refused = List.of(CycleTop.class, CycleMember.class, RingFirst.class, RingMiddle.class,
				RingLast.class, LatticeSide.class, LatticeTop.class, LatticeLoop.class, RefusedLoopHoldingGoodLoop.class);
		int threads = 8;
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int round = 0; round < 50; round++) {
				DataBindContext shared = DataBindContext.builder().build();
				CyclicBarrier start = new CyclicBarrier(threads);
				List<Future<?>> asks = new ArrayList<>();
				for (int t = 0; t < threads; t++) {
					List<Class<?>> order = new ArrayList<>(refused);
					Collections.rotate(order, t);
					asks.add(pool.submit(() -> {
						start.await();
						for (Class<?> type : order) {
							assertEquals(PROTECTED_CONSTRUCTOR, message(shared, type), type.getName());
							assertGoodLoop(shared);
						}
						return null;
					}));
				}
				for (Future<?> ask : asks) {
					ask.get();
				}
			}
		}
	}

	private static DataClassRecord assertGoodLoop(DataBindContext context) throws DataBindException {
		DataClassRecord loop = assertInstanceOf(DataClassRecord.class, context.getDescriptor(GoodLoop.class));
		assertSame(context.getDescriptor(GoodLoop.class), next(loop).dataClass());
		return loop;
	}

	private static DataClassField next(DataClassRecord loop) {
		return Stream.of(loop.fields()).filter(field -> field.name().equals("next")).findFirst().orElseThrow();
	}

	// --- refused over the super class constructor a hand-written immutable class calls

	private static final List<Class<?>> SUPER_REFUSALS = List.of(RecordRefusals.CallsProtectedSuper.class,
			RecordRefusals.CallsPackagePrivateSuper.class, RecordRefusals.ReachesProtectedSuper.class,
			RecordRefusals.CallsTransformingSuper.class, RecordRefusals.CallsDroppingSuper.class,
			RecordRefusals.HoldsCallsProtectedSuper.class, RecordRefusals.HoldsReachesProtectedSuper.class,
			RecordRefusals.HoldsCallsTransformingSuper.class);

	private static String superNotPublic(Class<?> type, Class<?> superClass) {
		return type.getName() + "'s constructor calls " + superClass.getName()
				+ ".<init>(java.lang.String), which is not a public constructor of its super class";
	}

	private static String superParameterNotStored(Class<?> type, Class<?> superClass, String parameters, int index) {
		return type.getName() + "'s constructor calls " + superClass.getName() + ".<init>(" + parameters
				+ "), which does not store its parameter " + index + " unchanged in a field";
	}

	@Test
	void aNonPublicSuperConstructorIsRefusedNamingTheClassTheSuperClassAndTheConstructorWithTheCauseKept() {
		assertAll(
				() -> assertSuperNotPublic(RecordRefusals.CallsProtectedSuper.class,
						RecordRefusals.ProtectedSuperBase.class),
				() -> assertSuperNotPublic(RecordRefusals.CallsPackagePrivateSuper.class,
						RecordRefusals.PackagePrivateSuperBase.class));
	}

	private void assertSuperNotPublic(Class<?> type, Class<?> superClass) {
		DataBindException refused = refusal(context, type);
		assertEquals(superNotPublic(type, superClass), refused.getMessage());
		assertInstanceOf(NoSuchMethodException.class, refused.getCause());
	}

	@Test
	void aNonPublicSuperConstructorBeneathAPublicOneIsRefusedNamingTheClassThatCallsIt() {
		assertEquals(superNotPublic(RecordRefusals.CallsProtectedSuper.class, RecordRefusals.ProtectedSuperBase.class),
				message(RecordRefusals.ReachesProtectedSuper.class));
	}

	@Test
	void aSuperConstructorThatTransformsOrDropsAnArgumentIsRefusedAsItself() {
		DataBindException transforms = refusal(context, RecordRefusals.CallsTransformingSuper.class);
		DataBindException drops = refusal(context, RecordRefusals.CallsDroppingSuper.class);
		assertAll(
				() -> assertEquals(superParameterNotStored(RecordRefusals.CallsTransformingSuper.class,
						RecordRefusals.TransformingSuperBase.class, "java.lang.String", 0), transforms.getMessage()),
				() -> assertNull(transforms.getCause()),
				() -> assertEquals(superParameterNotStored(RecordRefusals.CallsDroppingSuper.class,
						RecordRefusals.DroppingSuperBase.class, "java.lang.String, java.lang.String", 1),
						drops.getMessage()),
				() -> assertNull(drops.getCause()));
	}

	/** No compiler writes such a store, so the class is generated; the JVM resolves the field only when it runs. */
	@Test
	void aConstructorStoringIntoAFieldThatResolvesToNoneIsRefusedNamingTheClassAndField() throws Exception {
		Class<?> type = StoresIntoMissingField.define();
		DataBindException refused = refusal(context, type);
		assertEquals(type.getName() + "'s constructor assigns field " + type.getName() + ".missing, which resolves "
				+ "to no field of the class or a super class", refused.getMessage());
		assertNull(refused.getCause());
	}

	@Test
	void aSuperConstructorRefusalHeldAsAFieldIsTheMessage() {
		String notPublic = superNotPublic(RecordRefusals.CallsProtectedSuper.class,
				RecordRefusals.ProtectedSuperBase.class);
		assertAll(
				() -> assertEquals(notPublic, message(RecordRefusals.HoldsCallsProtectedSuper.class)),
				() -> assertEquals(notPublic, message(RecordRefusals.HoldsReachesProtectedSuper.class)),
				() -> assertEquals(message(RecordRefusals.CallsTransformingSuper.class),
						message(RecordRefusals.HoldsCallsTransformingSuper.class)));
	}

	@Test
	void aSuperConstructorRefusalCarriesNoUncheckedExceptionAnywhereOnItsCauseChain() {
		assertAll(SUPER_REFUSALS.stream().map(type -> () -> {
			assertNoGenericWrapper(context, type);
			for (Throwable at = refusal(context, type); at != null; at = at.getCause()) {
				assertFalse(at instanceof RuntimeException, type.getName() + ": " + at);
			}
		}));
	}

	@Test
	void aSuperConstructorRefusalIsRepeatableAndLeavesTheContextDescribingGoodClasses() throws DataBindException {
		DataBindContext fresh = DataBindContext.builder().build();
		assertAll(SUPER_REFUSALS.stream().map(type -> () -> {
			String first = message(fresh, type);
			assertEquals(first, message(fresh, type));
			assertEquals(first, message(DataBindContext.builder().build(), type));
		}));
		assertEquals(2, assertInstanceOf(DataClassRecord.class, fresh.getDescriptor(Good.class)).fields().length);
	}

	/** Loads the one class it generates and serves its class file, which the analysis reads. */
	private static final class StoresIntoMissingField extends ClassLoader {

		private static final String NAME = "generated.StoresIntoMissingField";

		private static final ClassDesc SELF = ClassDesc.of(NAME);

		private final byte[] bytes = ClassFile.of().build(SELF, type -> type
				.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
				.withMethod(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String),
						ClassFile.ACC_PUBLIC, constructor -> constructor
								.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(Record.class.describeConstable()
										.orElseThrow())))
								.withCode(code -> code.aload(0)
										.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
												ConstantDescs.MTD_void)
										.aload(0).aload(1).putfield(SELF, "missing", ConstantDescs.CD_String)
										.return_())));

		private StoresIntoMissingField() {
			super(RecordRefusalTest.class.getClassLoader());
		}

		static Class<?> define() throws ClassNotFoundException {
			return new StoresIntoMissingField().loadClass(NAME);
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (!name.equals(NAME)) {
				throw new ClassNotFoundException(name);
			}
			return defineClass(name, bytes, 0, bytes.length);
		}

		@Override
		public InputStream getResourceAsStream(String name) {
			return name.equals(NAME.replace('.', '/') + ".class") ? new ByteArrayInputStream(bytes)
					: super.getResourceAsStream(name);
		}
	}

	// --- classes the public lookup cannot reach

	static class HiddenEmpty {

		@Record
		public HiddenEmpty() {
		}
	}

	static class HiddenTakingArguments {

		private final String text;
		private final int count;
		private final String[] lines;

		@Record
		public HiddenTakingArguments(@Unbound String text, @Unbound int count, @Unbound String[] lines) {
			this.text = text;
			this.count = count;
			this.lines = lines;
		}
	}

	private record HiddenRecord(String text) {

		public HiddenRecord {
		}
	}

	static class HiddenBridge implements DataBridge<String, String> {

		public HiddenBridge() {
		}

		@Override
		public String toData(String b) {
			return b;
		}

		@Override
		public String toObject(String s) {
			return s;
		}
	}

	public record UsesHiddenBridge(@Field(bridge = HiddenBridge.class) String value) {
	}

	private record HiddenProfiledRecord(String text, int count) {

		public HiddenProfiledRecord {
		}

		@Profile(value = "v1", fields = { "text" })
		public HiddenProfiledRecord(String text) {
			this(text, 0);
		}
	}

	@Atom
	static final class HiddenAtom {

		private final String code;

		@Atom
		public HiddenAtom(String code) {
			this.code = code;
		}

		@Atom
		public String toData() {
			return code;
		}
	}

	@Atom
	public static final class AtomWithHiddenToData {

		private final String code;

		@Atom
		public AtomWithHiddenToData(String code) {
			this.code = code;
		}

		@Atom
		String toData() {
			return code;
		}
	}

	@Atom
	static final class HiddenAtomFactory {

		private final String code;

		private HiddenAtomFactory(String code) {
			this.code = code;
		}

		@Atom
		public static HiddenAtomFactory of(String code) {
			return new HiddenAtomFactory(code);
		}

		@Atom
		public String code() {
			return code;
		}
	}

	@Atom
	public static final class AtomFactoryWithHiddenAccessor {

		private final String code;

		private AtomFactoryWithHiddenAccessor(String code) {
			this.code = code;
		}

		@Atom
		public static AtomFactoryWithHiddenAccessor of(String code) {
			return new AtomFactoryWithHiddenAccessor(code);
		}

		@Atom
		String code() {
			return code;
		}
	}

	@Tuple
	private record HiddenTuple(String text) {

		public HiddenTuple {
		}
	}

	@Tuple
	public record TupleHoldsRefused(OrderCountMismatch value) {
	}

	@Transparent
	private record HiddenTransparent(String value) {

		public HiddenTransparent {
		}
	}

	public record Wrapped(String text) {
	}

	static final class HiddenToData implements ToData<Wrapped> {

		private final String text;

		public HiddenToData(Wrapped wrapped) {
			this.text = wrapped.text();
		}

		@Override
		public Wrapped toData() {
			return new Wrapped(text);
		}
	}

	static final class HiddenList<T> extends ArrayList<T> {

		@Serial
		private static final long serialVersionUID = 1L;

		public HiddenList(int capacity) {
			super(capacity);
		}
	}

	static final class HiddenMap<K, V> extends LinkedHashMap<K, V> {

		@Serial
		private static final long serialVersionUID = 1L;

		public HiddenMap(int capacity) {
			super(capacity);
		}
	}

	public record HoldsHiddenList(HiddenList<String> items) {
	}

	public record HoldsHiddenMap(HiddenMap<String, String> items) {
	}

	// --- helpers

	private String message(Class<?> type) {
		return message(context, type);
	}

	private static String message(DataBindContext context, Class<?> type) {
		return refusal(context, type).getMessage();
	}

	private static DataBindException refusal(DataBindContext context, Class<?> type) {
		return assertThrows(DataBindException.class, () -> context.getDescriptor(type));
	}

	private static void assertNoGenericWrapper(DataBindContext context, Class<?> type) {
		DataBindException refused = refusal(context, type);
		assertNotNull(refused.getMessage(), type.getName());
		for (Throwable at = refused; at != null; at = at.getCause()) {
			assertNotEquals("Failed to resolve", at.getMessage(), type.getName());
		}
	}
}
