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
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.AnnotatedMembers.AccessorBridgeBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.AccessorFieldBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.AccessorRequiredBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.AccessorUnionBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.ConstructorAccessor;
import io.ltr8.test.data.AnnotatedMembers.ConstructorAgreeingMembers;
import io.ltr8.test.data.AnnotatedMembers.ConstructorAttributesOnThreeMembers;
import io.ltr8.test.data.AnnotatedMembers.ConstructorBridgeOnOne;
import io.ltr8.test.data.AnnotatedMembers.ConstructorBridgesDiffer;
import io.ltr8.test.data.AnnotatedMembers.ConstructorComputedIntoAnnotatedField;
import io.ltr8.test.data.AnnotatedMembers.ConstructorDisagreementBesideUnstoredParameter;
import io.ltr8.test.data.AnnotatedMembers.ConstructorFieldBesideAccessor;
import io.ltr8.test.data.AnnotatedMembers.ConstructorFieldBesideParameter;
import io.ltr8.test.data.AnnotatedMembers.ConstructorNamedAccessor;
import io.ltr8.test.data.AnnotatedMembers.ConstructorNamedAccessorRequired;
import io.ltr8.test.data.AnnotatedMembers.ConstructorNamedAccessorUnion;
import io.ltr8.test.data.AnnotatedMembers.ConstructorParameter;
import io.ltr8.test.data.AnnotatedMembers.ConstructorParameterBesideAccessor;
import io.ltr8.test.data.AnnotatedMembers.ConstructorParameterInTwoFields;
import io.ltr8.test.data.AnnotatedMembers.ConstructorRequiredDiffers;
import io.ltr8.test.data.AnnotatedMembers.ConstructorStoredField;
import io.ltr8.test.data.AnnotatedMembers.ConstructorUnionAccessor;
import io.ltr8.test.data.AnnotatedMembers.ConstructorUnionField;
import io.ltr8.test.data.AnnotatedMembers.ConstructorUnionFieldBesideAccessor;
import io.ltr8.test.data.AnnotatedMembers.ConstructorUnionFieldBesideParameter;
import io.ltr8.test.data.AnnotatedMembers.ConstructorUnionParameter;
import io.ltr8.test.data.AnnotatedMembers.ConstructorUnionUnstoredParameter;
import io.ltr8.test.data.AnnotatedMembers.ExplicitCanonicalParameterBesideAccessor;
import io.ltr8.test.data.AnnotatedMembers.ExplicitCanonicalParameterBridge;
import io.ltr8.test.data.AnnotatedMembers.ExplicitCanonicalParameterField;
import io.ltr8.test.data.AnnotatedMembers.ExplicitCanonicalParameterUnion;
import io.ltr8.test.data.AnnotatedMembers.HeaderBridgeBesideAccessorBridge;
import io.ltr8.test.data.AnnotatedMembers.HeaderField;
import io.ltr8.test.data.AnnotatedMembers.HeaderFieldExplicitAccessor;
import io.ltr8.test.data.AnnotatedMembers.HeaderUnion;
import io.ltr8.test.data.AnnotatedMembers.HeaderUnionExplicitAccessor;
import io.ltr8.test.data.AnnotatedMembers.HeaderValueAccessorRequired;
import io.ltr8.test.data.AnnotatedMembers.MergedGetterBesideSetter;
import io.ltr8.test.data.AnnotatedMembers.MergedGetterField;
import io.ltr8.test.data.AnnotatedMembers.MergedPublicField;
import io.ltr8.test.data.AnnotatedMembers.MergedPublicFieldUnion;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterBesideStoredField;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterField;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterParameter;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterParameterBesideStoredField;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterRequired;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterUnbound;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterUnion;
import io.ltr8.test.data.AnnotatedMembers.PairAgreeingMembers;
import io.ltr8.test.data.AnnotatedMembers.PairBridgeOnOne;
import io.ltr8.test.data.AnnotatedMembers.PairDisagreementBesideOrphan;
import io.ltr8.test.data.AnnotatedMembers.PairGetter;
import io.ltr8.test.data.AnnotatedMembers.PairGetterBesidePrivateField;
import io.ltr8.test.data.AnnotatedMembers.PairGetterBesideSetter;
import io.ltr8.test.data.AnnotatedMembers.PairGetterBesideSetterParameter;
import io.ltr8.test.data.AnnotatedMembers.PairGetterUnion;
import io.ltr8.test.data.AnnotatedMembers.PairInheritedPrivateField;
import io.ltr8.test.data.AnnotatedMembers.PairOrderFollowsPrivateFieldName;
import io.ltr8.test.data.AnnotatedMembers.PairOrphanPrivateField;
import io.ltr8.test.data.AnnotatedMembers.PairPrivateField;
import io.ltr8.test.data.AnnotatedMembers.PairPrivateFieldUnion;
import io.ltr8.test.data.AnnotatedMembers.PairRequiredDiffers;
import io.ltr8.test.data.AnnotatedMembers.PairSetter;
import io.ltr8.test.data.AnnotatedMembers.PairSetterBesideItsParameter;
import io.ltr8.test.data.AnnotatedMembers.PairSetterBesidePrivateField;
import io.ltr8.test.data.AnnotatedMembers.PairSetterParameter;
import io.ltr8.test.data.AnnotatedMembers.PairSetterParameterUnbound;
import io.ltr8.test.data.AnnotatedMembers.PairSetterParameterUnion;
import io.ltr8.test.data.AnnotatedMembers.PairUnionsDiffer;
import io.ltr8.test.data.AnnotatedMembers.ProfiledAccessorBesideParameterField;
import io.ltr8.test.data.AnnotatedMembers.ProfiledAccessorField;
import io.ltr8.test.data.AnnotatedMembers.ProfiledFieldsBesideParameterField;
import io.ltr8.test.data.AnnotatedMembers.ProfiledHeaderBridge;
import io.ltr8.test.data.AnnotatedMembers.ProfiledHeaderUnion;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterBridge;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterField;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterOverDisagreement;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterRequiredBesideHeaderName;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterUnion;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterUnionBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.ProfiledSilentParameterBesideDisagreement;
import io.ltr8.test.data.AnnotatedMembers.ProfiledSilentParameterBesideUnionDisagreement;
import io.ltr8.test.data.AnnotatedMembers.ProfiledUnboundAccessor;
import io.ltr8.test.data.AnnotatedMembers.PublicFieldNamed;
import io.ltr8.test.data.AnnotatedMembers.PublicFieldUnion;
import io.ltr8.test.data.AnnotatedMembers.UnboundAccessor;
import io.ltr8.test.data.AnnotatedMembers.UnboundExplicitCanonicalParameter;
import io.ltr8.test.data.AnnotatedMembers.UnboundHeader;
import io.ltr8.test.data.BridgedFields.NumberAsText;
import io.ltr8.test.data.BridgedFields.RecordingNumberAsText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every member of a component is read for {@code @Field}, {@code @Union} and {@code @Unbound}, on every class kind.
 * {@code @Field} merges by attribute, a default counting as unstated; two members stating one attribute differently,
 * or carrying differing {@code @Union}s, refuse the class. On a {@code @Profile} path the profile constructor's
 * parameter prevails over the record's own members for each attribute it states, and for {@code @Union}.
 *
 * <p>Each class is described as its fields, {@code name[!]:bound[ set]} ({@code !} required, the bound the data
 * class or a union's members; a bridged field's data class is the bridge's), or as its refusal.
 */
class ComponentAnnotationPrecedenceTest {

	private static final DataBindContext PLAIN = DataBindContext.builder().build();

	private static final DataBindContext PROFILED = DataBindContext.builder().profile("p").build();

	private static final String OBJECT = "refused: Unable to find a valid data conversion for class: class "
			+ "java.lang.Object";

	private static final String BOTH = "{java.lang.String.class, java.lang.Integer.class}";

	private static final String TEXT = "{java.lang.String.class}";

	@Test
	void aCanonicalRecordReadsFieldFromItsFieldAccessorAndParameter() {
		assertAll(
				described(PLAIN, HeaderField.class, "a:String"),
				described(PLAIN, HeaderFieldExplicitAccessor.class, "a:String"),
				described(PLAIN, AccessorRequiredBesideHeader.class, "x!:String"),
				described(PLAIN, AccessorBridgeBesideHeader.class, "x:String"),
				described(PLAIN, HeaderValueAccessorRequired.class, "a!:String"),
				described(PLAIN, ExplicitCanonicalParameterField.class, "p:String"),
				described(PLAIN, ExplicitCanonicalParameterBridge.class, "x:String"),
				described(PLAIN, ProfiledAccessorField.class, "acc:String, y:String"),
				described(PLAIN, ProfiledAccessorBesideParameterField.class, "acc!:String, y:String"),
				described(PLAIN, ProfiledParameterBridge.class, "x:Integer, y:String"),
				described(PLAIN, ProfiledParameterBesideHeader.class, "a:String, y:String"),
				described(PLAIN, ProfiledParameterRequiredBesideHeaderName.class, "a:String, y:String"),
				described(PLAIN, ProfiledHeaderBridge.class, "x:String, y:String"));
	}

	@Test
	void aCanonicalRecordRefusesMembersStatingAnAttributeDifferently() {
		assertAll(
				described(PLAIN, AccessorFieldBesideHeader.class, fieldDiffers(AccessorFieldBesideHeader.class, "x",
						value("a"), field(AccessorFieldBesideHeader.class, "x"),
						value("b"), accessor(AccessorFieldBesideHeader.class, "x"))),
				described(PLAIN, ExplicitCanonicalParameterBesideAccessor.class,
						fieldDiffers(ExplicitCanonicalParameterBesideAccessor.class, "x",
								value("b"), accessor(ExplicitCanonicalParameterBesideAccessor.class, "x"),
								value("p"), canonicalParameter(ExplicitCanonicalParameterBesideAccessor.class, 0,
										"String"))),
				described(PLAIN, HeaderBridgeBesideAccessorBridge.class,
						fieldDiffers(HeaderBridgeBesideAccessorBridge.class, "x",
								bridge(NumberAsText.class), field(HeaderBridgeBesideAccessorBridge.class, "x"),
								bridge(RecordingNumberAsText.class),
								accessor(HeaderBridgeBesideAccessorBridge.class, "x"))),
				described(PLAIN, ProfiledParameterOverDisagreement.class,
						fieldDiffers(ProfiledParameterOverDisagreement.class, "x",
								value("a"), field(ProfiledParameterOverDisagreement.class, "x"),
								value("c"), accessor(ProfiledParameterOverDisagreement.class, "x"))));
	}

	@Test
	void aCanonicalRecordReadsUnionFromItsFieldAccessorAndParameter() {
		assertAll(
				described(PLAIN, HeaderUnion.class, "tag:union(String|Integer)"),
				described(PLAIN, HeaderUnionExplicitAccessor.class, "tag:union(String|Integer)"),
				described(PLAIN, ExplicitCanonicalParameterUnion.class, "tag:union(String|Integer)"),
				described(PLAIN, ProfiledHeaderUnion.class, "tag:union(String|Integer), y:String"),
				described(PLAIN, ProfiledParameterUnionBesideHeader.class, "tag:union(String), y:String"),
				described(PLAIN, AccessorUnionBesideHeader.class, unionsDiffer(AccessorUnionBesideHeader.class, "tag",
						BOTH, field(AccessorUnionBesideHeader.class, "tag"),
						TEXT, accessor(AccessorUnionBesideHeader.class, "tag"))));
	}

	@Test
	void aCanonicalRecordReadsUnboundFromAnyMember() {
		assertAll(
				described(PLAIN, UnboundHeader.class, "name:String"),
				described(PLAIN, UnboundAccessor.class, "name:String"),
				described(PLAIN, UnboundExplicitCanonicalParameter.class, "name:String"),
				described(PLAIN, ProfiledUnboundAccessor.class, "name:String, tier:String"));
	}

	@Test
	void aProfiledRecordTakesEachAttributeItsParameterStatesFromTheParameter() {
		assertAll(
				described(PROFILED, ProfiledFieldsBesideParameterField.class, "b:String"),
				described(PROFILED, ProfiledParameterField.class, "x!:String"),
				described(PROFILED, ProfiledParameterBridge.class, "x:String"),
				described(PROFILED, ProfiledParameterBesideHeader.class, "b:String"),
				described(PROFILED, ProfiledParameterOverDisagreement.class, "b:String"));
	}

	@Test
	void aProfiledRecordTakesEachAttributeItsParameterLeavesFromTheRecordsOwnMembers() {
		assertAll(
				described(PROFILED, ProfiledAccessorField.class, "acc:String"),
				described(PROFILED, ProfiledAccessorBesideParameterField.class, "x!:String"),
				described(PROFILED, ProfiledParameterRequiredBesideHeaderName.class, "a!:String"),
				described(PROFILED, ProfiledHeaderBridge.class, "x:String"),
				described(PROFILED, ProfiledSilentParameterBesideDisagreement.class,
						fieldDiffers(ProfiledSilentParameterBesideDisagreement.class, "x",
								value("a"), field(ProfiledSilentParameterBesideDisagreement.class, "x"),
								value("c"), accessor(ProfiledSilentParameterBesideDisagreement.class, "x"))));
	}

	@Test
	void aProfiledRecordTakesItsParametersUnionOverTheRecordsOwn() {
		assertAll(
				described(PROFILED, ProfiledParameterUnion.class, "tag:union(String|Integer)"),
				described(PROFILED, ProfiledParameterUnionBesideHeader.class, "tag:union(String|Integer)"),
				described(PROFILED, ProfiledHeaderUnion.class, "tag:union(String|Integer)"),
				described(PROFILED, ProfiledSilentParameterBesideUnionDisagreement.class,
						unionsDiffer(ProfiledSilentParameterBesideUnionDisagreement.class, "tag",
								BOTH, field(ProfiledSilentParameterBesideUnionDisagreement.class, "tag"),
								TEXT, accessor(ProfiledSilentParameterBesideUnionDisagreement.class, "tag"))));
	}

	@Test
	void aProfiledRecordReadsUnboundFromItsAccessor() {
		assertAll(described(PROFILED, ProfiledUnboundAccessor.class, "name:String"));
	}

	@Test
	void aConstructorClassMergesFieldFromItsStoredFieldParameterAndAccessor() {
		assertAll(
				described(PLAIN, ConstructorStoredField.class, "a:String"),
				described(PLAIN, ConstructorParameter.class, "a:String"),
				described(PLAIN, ConstructorAccessor.class, "a:String"),
				described(PLAIN, ConstructorAgreeingMembers.class, "a:String"),
				described(PLAIN, ConstructorNamedAccessor.class, "x:String"),
				described(PLAIN, ConstructorRequiredDiffers.class, "x!:String"),
				described(PLAIN, ConstructorBridgeOnOne.class, "x:String"),
				described(PLAIN, ConstructorNamedAccessorRequired.class, "x!:String"),
				described(PLAIN, ConstructorComputedIntoAnnotatedField.class, "x!:String"),
				described(PLAIN, ConstructorAttributesOnThreeMembers.class, "a!:String"));
	}

	@Test
	void aConstructorClassRefusesMembersStatingAnAttributeDifferently() {
		assertAll(
				described(PLAIN, ConstructorFieldBesideParameter.class,
						fieldDiffers(ConstructorFieldBesideParameter.class, "x",
								value("a"), field(ConstructorFieldBesideParameter.class, "x"),
								value("b"), parameter(ConstructorFieldBesideParameter.class, 0, "String"))),
				described(PLAIN, ConstructorFieldBesideAccessor.class,
						fieldDiffers(ConstructorFieldBesideAccessor.class, "x",
								value("a"), field(ConstructorFieldBesideAccessor.class, "x"),
								value("b"), accessor(ConstructorFieldBesideAccessor.class, "x"))),
				described(PLAIN, ConstructorParameterBesideAccessor.class,
						fieldDiffers(ConstructorParameterBesideAccessor.class, "x",
								value("a"), parameter(ConstructorParameterBesideAccessor.class, 0, "String"),
								value("b"), accessor(ConstructorParameterBesideAccessor.class, "x"))),
				described(PLAIN, ConstructorBridgesDiffer.class,
						fieldDiffers(ConstructorBridgesDiffer.class, "x",
								bridge(NumberAsText.class), parameter(ConstructorBridgesDiffer.class, 0, "Integer"),
								bridge(RecordingNumberAsText.class), accessor(ConstructorBridgesDiffer.class, "x"))));
	}

	@Test
	void aConstructorClassRefusesDifferingFieldsBeforeAnUnstoredParameter() {
		assertAll(described(PLAIN, ConstructorDisagreementBesideUnstoredParameter.class,
				fieldDiffers(ConstructorDisagreementBesideUnstoredParameter.class, "x",
						value("a"), field(ConstructorDisagreementBesideUnstoredParameter.class, "x"),
						value("b"), parameter(ConstructorDisagreementBesideUnstoredParameter.class, 0,
								"String, String"))));
	}

	@Test
	void aParameterStoredInTwoFieldsIsRefusedForThat() {
		assertAll(described(PLAIN, ConstructorParameterInTwoFields.class, "refused: " + ConstructorParameterInTwoFields
				.class.getName() + "'s constructor stores its parameter 0 in more than one field"));
	}

	@Test
	void aConstructorClassReadsUnionFromItsStoredFieldParameterAndAccessor() {
		assertAll(
				described(PLAIN, ConstructorUnionField.class, "tag:union(String|Integer)"),
				described(PLAIN, ConstructorUnionParameter.class, "tag:union(String|Integer)"),
				described(PLAIN, ConstructorUnionAccessor.class, "tag:union(String|Integer)"),
				described(PLAIN, ConstructorNamedAccessorUnion.class, "tag:union(String|Integer)"),
				described(PLAIN, ConstructorUnionFieldBesideParameter.class,
						unionsDiffer(ConstructorUnionFieldBesideParameter.class, "tag",
								TEXT, field(ConstructorUnionFieldBesideParameter.class, "tag"),
								BOTH, parameter(ConstructorUnionFieldBesideParameter.class, 0, "Object"))),
				described(PLAIN, ConstructorUnionFieldBesideAccessor.class,
						unionsDiffer(ConstructorUnionFieldBesideAccessor.class, "tag",
								BOTH, field(ConstructorUnionFieldBesideAccessor.class, "tag"),
								TEXT, accessor(ConstructorUnionFieldBesideAccessor.class, "tag"))),
				described(PLAIN, ConstructorUnionUnstoredParameter.class, "refused: "
						+ ConstructorUnionUnstoredParameter.class.getName() + "'s constructor does not assign its "
						+ "@Union parameter 0 unchanged to a field, and no @Field names it"));
	}

	@Test
	void aPublicFieldReadsFieldAndUnion() {
		assertAll(
				described(PLAIN, PublicFieldNamed.class, "m:String set, z:String set"),
				described(PLAIN, PublicFieldUnion.class, "tag:union(String|Integer) set"));
	}

	@Test
	void aPairMergesFieldFromItsGetterSetterAndPrivateField() {
		assertAll(
				described(PLAIN, PairGetter.class, "a:String set"),
				described(PLAIN, PairSetter.class, "a:String set"),
				described(PLAIN, PairPrivateField.class, "a:String set"),
				described(PLAIN, PairAgreeingMembers.class, "a:String set"),
				described(PLAIN, PairRequiredDiffers.class, "x!:String set"),
				described(PLAIN, PairBridgeOnOne.class, "x:String set"),
				described(PLAIN, PairInheritedPrivateField.class, "a:String set"));
	}

	@Test
	void aPairRefusesMembersStatingAnAttributeDifferently() {
		assertAll(
				described(PLAIN, PairGetterBesideSetter.class, fieldDiffers(PairGetterBesideSetter.class, "x",
						value("a"), getter(PairGetterBesideSetter.class, "getX"),
						value("b"), setter(PairGetterBesideSetter.class, "setX", "String"))),
				described(PLAIN, PairGetterBesidePrivateField.class, fieldDiffers(PairGetterBesidePrivateField.class,
						"x", value("a"), getter(PairGetterBesidePrivateField.class, "getX"),
						value("b"), privateField(PairGetterBesidePrivateField.class, "x"))),
				described(PLAIN, PairSetterBesidePrivateField.class, fieldDiffers(PairSetterBesidePrivateField.class,
						"x", value("a"), setter(PairSetterBesidePrivateField.class, "setX", "String"),
						value("b"), privateField(PairSetterBesidePrivateField.class, "x"))));
	}

	@Test
	void aPairRefusesAPrivateFieldOfNoComponentAfterDifferingFields() {
		assertAll(
				described(PLAIN, PairOrphanPrivateField.class,
						"refused: Could not find matching getter/setter for field 'orphan'"),
				described(PLAIN, PairDisagreementBesideOrphan.class, fieldDiffers(PairDisagreementBesideOrphan.class,
						"x", value("a"), getter(PairDisagreementBesideOrphan.class, "getX"),
						value("b"), setter(PairDisagreementBesideOrphan.class, "setX", "String"))));
	}

	@Test
	void aPairReadsUnionFromItsGetterSetterAndPrivateField() {
		assertAll(
				described(PLAIN, PairGetterUnion.class, "tag:union(String|Integer) set"),
				described(PLAIN, PairPrivateFieldUnion.class, "tag:union(String|Integer) set"),
				described(PLAIN, PairUnionsDiffer.class, unionsDiffer(PairUnionsDiffer.class, "tag",
						TEXT, getter(PairUnionsDiffer.class, "getTag"),
						BOTH, privateField(PairUnionsDiffer.class, "tag"))));
	}

	@Test
	void aSettersParameterIsReadLikeAnyMember() {
		assertAll(
				described(PLAIN, PairSetterParameter.class, "a:String set"),
				described(PLAIN, PairSetterParameterUnion.class, "tag:union(String|Integer) set"),
				described(PLAIN, PairSetterParameterUnbound.class, "name:String set"),
				described(PLAIN, PairSetterBesideItsParameter.class, "x!:String set"),
				described(PLAIN, MergedSetterParameter.class, "s:String set"));
	}

	@Test
	void aSettersParameterStatingAnAttributeDifferentlyIsRefused() {
		assertAll(
				refusedAlone(PairGetterBesideSetterParameter.class,
						fieldDiffers(PairGetterBesideSetterParameter.class, "x",
								value("a"), getter(PairGetterBesideSetterParameter.class, "getX"),
								value("b"), setterParameter(PairGetterBesideSetterParameter.class, "setX", "String"))),
				refusedAlone(MergedSetterParameterBesideStoredField.class,
						fieldDiffers(MergedSetterParameterBesideStoredField.class, "count",
								value("a"), field(MergedSetterParameterBesideStoredField.class, "count"),
								value("s"), setterParameter(MergedSetterParameterBesideStoredField.class, "setCount",
										"String"))));
	}

	@Test
	void aPairNamedByItsSettersParameterRoundTripsThroughBothMappers() throws Throwable {
		PairSetterParameter value = new PairSetterParameter();
		value.setX("v");
		MapMapper maps = new MapMapper(PLAIN);
		assertEquals(Map.of("a", "v"), maps.toMap(value));
		assertEquals("v", ((PairSetterParameter) maps.toObject(PairSetterParameter.class, maps.toMap(value))).getX());
		ArrayMapper arrays = new ArrayMapper(PLAIN);
		assertArrayEquals(new Object[] { "v" }, arrays.toArray(value));
		assertEquals("v", arrays.toObject(PairSetterParameter.class, arrays.toArray(value)).getX());
	}

	@Test
	void pairsAreOrderedByTheirFinalWireNames() {
		assertAll(described(PLAIN, PairOrderFollowsPrivateFieldName.class, "m:String set, z:String set"));
	}

	@Test
	void aSetterMergedIntoAConstructorComponentIsReadLikeAnyMember() {
		assertAll(
				described(PLAIN, MergedSetterField.class, "s:String set"),
				described(PLAIN, MergedSetterRequired.class, "count!:String set"),
				described(PLAIN, MergedSetterUnion.class, "tag:union(String|Integer) set"),
				described(PLAIN, MergedSetterUnbound.class, "name:String"),
				described(PLAIN, MergedGetterField.class, "g:String set"),
				described(PLAIN, MergedSetterBesideStoredField.class,
						fieldDiffers(MergedSetterBesideStoredField.class, "count",
								value("a"), field(MergedSetterBesideStoredField.class, "count"),
								value("s"), setter(MergedSetterBesideStoredField.class, "setCount", "String"))),
				described(PLAIN, MergedGetterBesideSetter.class, fieldDiffers(MergedGetterBesideSetter.class, "count",
						value("g"), getter(MergedGetterBesideSetter.class, "getCount"),
						value("s"), setter(MergedGetterBesideSetter.class, "setCount", "String"))));
	}

	@Test
	void aPublicFieldMergedIntoAConstructorComponentIsReadLikeAnyMember() {
		assertAll(
				described(PLAIN, MergedPublicField.class, "a:String set"),
				described(PLAIN, MergedPublicFieldUnion.class, "tag:union(String|Integer) set"));
	}

	@Test
	void aCanonicalRecordRoundTripsThroughBothMappers() throws Throwable {
		AccessorBridgeBesideHeader value = new AccessorBridgeBesideHeader(42);
		roundTrip(PLAIN, value, Map.of("x", "42"), new Object[] { "42" });
	}

	@Test
	void aProfiledRecordRoundTripsThroughBothMappers() throws Throwable {
		roundTrip(PROFILED, new ProfiledHeaderBridge(42, null), Map.of("x", "42"), new Object[] { "42" });
		roundTrip(PROFILED, new ProfiledParameterBesideHeader("v", null), Map.of("b", "v"), new Object[] { "v" });
	}

	@Test
	void aConstructorClassRoundTripsThroughBothMappers() throws Throwable {
		ConstructorAttributesOnThreeMembers value = new ConstructorAttributesOnThreeMembers(42);
		MapMapper maps = new MapMapper(PLAIN);
		assertEquals(Map.of("a", "42"), maps.toMap(value));
		assertEquals(42, ((ConstructorAttributesOnThreeMembers) maps.toObject(ConstructorAttributesOnThreeMembers.class,
				maps.toMap(value))).x());
		ArrayMapper arrays = new ArrayMapper(PLAIN);
		assertArrayEquals(new Object[] { "42" }, arrays.toArray(value));
		assertEquals(42, arrays.toObject(ConstructorAttributesOnThreeMembers.class, arrays.toArray(value)).x());
	}

	@Test
	void aPublicFieldRoundTripsThroughBothMappers() throws Throwable {
		PublicFieldUnion value = new PublicFieldUnion();
		value.tag = 7;
		MapMapper maps = new MapMapper(PLAIN);
		Map<String, Object> map = maps.toMap(value);
		assertEquals(7, ((PublicFieldUnion) maps.toObject(PublicFieldUnion.class, map)).tag);
		ArrayMapper arrays = new ArrayMapper(PLAIN);
		assertEquals(7, arrays.toObject(PublicFieldUnion.class, arrays.toArray(value)).tag);
	}

	@Test
	void aPairRoundTripsThroughBothMappers() throws Throwable {
		PairInheritedPrivateField value = new PairInheritedPrivateField();
		value.setX("v");
		MapMapper maps = new MapMapper(PLAIN);
		assertEquals(Map.of("a", "v"), maps.toMap(value));
		assertEquals("v", ((PairInheritedPrivateField) maps.toObject(PairInheritedPrivateField.class,
				maps.toMap(value))).getX());
		ArrayMapper arrays = new ArrayMapper(PLAIN);
		assertArrayEquals(new Object[] { "v" }, arrays.toArray(value));
		assertEquals("v", arrays.toObject(PairInheritedPrivateField.class, arrays.toArray(value)).getX());
	}

	@Test
	void aMergedSetterRoundTripsThroughBothMappers() throws Throwable {
		MergedSetterField value = new MergedSetterField("v");
		MapMapper maps = new MapMapper(PLAIN);
		assertEquals(Map.of("s", "v"), maps.toMap(value));
		assertEquals("v", ((MergedSetterField) maps.toObject(MergedSetterField.class, maps.toMap(value))).getCount());
		ArrayMapper arrays = new ArrayMapper(PLAIN);
		assertArrayEquals(new Object[] { "v" }, arrays.toArray(value));
		assertEquals("v", arrays.toObject(MergedSetterField.class, arrays.toArray(value)).getCount());
	}

	private static void roundTrip(DataBindContext context, Record value, Map<String, Object> map, Object[] array)
			throws Throwable {
		MapMapper maps = new MapMapper(context);
		assertEquals(map, maps.toMap(value));
		assertEquals(value, maps.toObject(value.getClass(), map));
		ArrayMapper arrays = new ArrayMapper(context);
		assertArrayEquals(array, arrays.toArray(value));
		assertEquals(value, arrays.toObject(value.getClass(), array));
	}

	private static String fieldDiffers(Class<?> type, String component, String first, String firstMember,
			String second, String secondMember) {
		return differ(type, component, "@Field(" + first + ")", firstMember, "@Field(" + second + ")", secondMember);
	}

	private static String unionsDiffer(Class<?> type, String component, String first, String firstMember,
			String second, String secondMember) {
		return differ(type, component, "@Union(" + first + ")", firstMember, "@Union(" + second + ")", secondMember);
	}

	private static String differ(Class<?> type, String component, String first, String firstMember, String second,
			String secondMember) {
		return "refused: %s's component '%s' has %s on %s but %s on %s".formatted(type.getName(), component, first,
				firstMember, second, secondMember);
	}

	private static String value(String name) {
		return "value = \"" + name + "\"";
	}

	private static String bridge(Class<?> bridge) {
		return "bridge = " + bridge.getName() + ".class";
	}

	private static String field(Class<?> type, String name) {
		return "the field " + type.getSimpleName() + "." + name;
	}

	private static String privateField(Class<?> type, String name) {
		return "the private field " + type.getSimpleName() + "." + name;
	}

	private static String accessor(Class<?> type, String name) {
		return "the accessor " + type.getSimpleName() + "." + name + "()";
	}

	private static String getter(Class<?> type, String name) {
		return "the getter " + type.getSimpleName() + "." + name + "()";
	}

	private static String setter(Class<?> type, String name, String parameter) {
		return "the setter " + type.getSimpleName() + "." + name + "(" + parameter + ")";
	}

	private static String setterParameter(Class<?> type, String name, String parameter) {
		return "parameter 0 of the setter " + type.getSimpleName() + "." + name + "(" + parameter + ")";
	}

	private static String parameter(Class<?> type, int index, String parameters) {
		return "parameter " + index + " of the constructor " + type.getSimpleName() + "(" + parameters + ")";
	}

	private static String canonicalParameter(Class<?> type, int index, String parameters) {
		return "parameter " + index + " of the canonical constructor " + type.getSimpleName() + "(" + parameters + ")";
	}

	private static Executable described(DataBindContext context, Class<?> type, String expected) {
		return () -> assertEquals(expected, describe(context, type), type.getSimpleName());
	}

	private static String describe(DataBindContext context, Class<?> type) {
		try {
			DataClassRecord record = (DataClassRecord) context.getDescriptor(type);
			return Stream.of(record.fields()).map(ComponentAnnotationPrecedenceTest::describe)
					.collect(Collectors.joining(", "));
		} catch (Exception e) {
			return "refused: " + e.getMessage();
		}
	}

	/** In two fresh contexts, {@code expected} less its "refused: " is the refusal's message, with no cause. */
	private static Executable refusedAlone(Class<?> type, String expected) {
		return () -> assertAll(Stream.of(1, 2).<Executable>map(run -> () -> assertEquals(
				List.of(expected.substring("refused: ".length())), messages(DataBindContext.builder().build(), type),
				type.getSimpleName() + ", context " + run)));
	}

	private static List<String> messages(DataBindContext context, Class<?> type) {
		List<String> messages = new ArrayList<>();
		try {
			context.getDescriptor(type);
		} catch (Exception e) {
			for (Throwable at = e; at != null; at = at.getCause()) {
				messages.add(at.getMessage());
			}
		}
		return messages;
	}

	private static String describe(DataClassField field) {
		DataClass data = field.dataClass();
		String bound = data instanceof DataClassUnion union ? Stream.of(union.memberTypes())
				.map(Class::getSimpleName).collect(Collectors.joining("|", "union(", ")"))
				: data.dataClass().getSimpleName();
		return field.name() + (field.isRequired() ? "!" : "") + ":" + bound
				+ (field.setter().isPresent() ? " set" : "");
	}
}
