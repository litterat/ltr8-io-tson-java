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
import io.ltr8.test.data.AnnotatedMembers.AccessorBridgeBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.AccessorFieldBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.AccessorRequiredBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.AccessorUnionBesideHeader;
import io.ltr8.test.data.AnnotatedMembers.ConstructorAccessor;
import io.ltr8.test.data.AnnotatedMembers.ConstructorAgreeingMembers;
import io.ltr8.test.data.AnnotatedMembers.ConstructorBridgeOnOne;
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
import io.ltr8.test.data.AnnotatedMembers.ExplicitCanonicalParameterField;
import io.ltr8.test.data.AnnotatedMembers.ExplicitCanonicalParameterUnion;
import io.ltr8.test.data.AnnotatedMembers.HeaderField;
import io.ltr8.test.data.AnnotatedMembers.HeaderFieldExplicitAccessor;
import io.ltr8.test.data.AnnotatedMembers.HeaderUnion;
import io.ltr8.test.data.AnnotatedMembers.HeaderUnionExplicitAccessor;
import io.ltr8.test.data.AnnotatedMembers.MergedGetterBesideSetter;
import io.ltr8.test.data.AnnotatedMembers.MergedGetterField;
import io.ltr8.test.data.AnnotatedMembers.MergedPublicField;
import io.ltr8.test.data.AnnotatedMembers.MergedPublicFieldUnion;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterBesideStoredField;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterField;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterRequired;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterUnbound;
import io.ltr8.test.data.AnnotatedMembers.MergedSetterUnion;
import io.ltr8.test.data.AnnotatedMembers.PairAgreeingMembers;
import io.ltr8.test.data.AnnotatedMembers.PairBridgeOnOne;
import io.ltr8.test.data.AnnotatedMembers.PairDisagreementBesideOrphan;
import io.ltr8.test.data.AnnotatedMembers.PairGetter;
import io.ltr8.test.data.AnnotatedMembers.PairGetterBesidePrivateField;
import io.ltr8.test.data.AnnotatedMembers.PairGetterBesideSetter;
import io.ltr8.test.data.AnnotatedMembers.PairGetterUnion;
import io.ltr8.test.data.AnnotatedMembers.PairOrphanPrivateField;
import io.ltr8.test.data.AnnotatedMembers.PairPrivateField;
import io.ltr8.test.data.AnnotatedMembers.PairPrivateFieldUnion;
import io.ltr8.test.data.AnnotatedMembers.PairRequiredDiffers;
import io.ltr8.test.data.AnnotatedMembers.PairSetter;
import io.ltr8.test.data.AnnotatedMembers.PairSetterBesidePrivateField;
import io.ltr8.test.data.AnnotatedMembers.ProfiledAccessorBesideParameterField;
import io.ltr8.test.data.AnnotatedMembers.ProfiledAccessorField;
import io.ltr8.test.data.AnnotatedMembers.ProfiledFieldsBesideParameterField;
import io.ltr8.test.data.AnnotatedMembers.ProfiledHeaderUnion;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterBridge;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterField;
import io.ltr8.test.data.AnnotatedMembers.ProfiledParameterUnion;
import io.ltr8.test.data.AnnotatedMembers.ProfiledUnboundAccessor;
import io.ltr8.test.data.AnnotatedMembers.PublicFieldNamed;
import io.ltr8.test.data.AnnotatedMembers.PublicFieldUnion;
import io.ltr8.test.data.AnnotatedMembers.UnboundAccessor;
import io.ltr8.test.data.AnnotatedMembers.UnboundExplicitCanonicalParameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which of a component's members the analysis reads {@code @Field}, {@code @Union} and {@code @Unbound} from, for
 * each way a class exposes a component, and what follows where several carry one. Two members whose {@code @Field}s
 * (or {@code @Union}s) differ in any attribute refuse the class; a member not read is ignored whatever it carries.
 *
 * <p>Each class is described as its fields, {@code name[!]:bound[ set]} ({@code !} required, the bound the data
 * class or a union's members), or as its refusal.
 */
class ComponentAnnotationPrecedenceTest {

	private static final DataBindContext PLAIN = DataBindContext.builder().build();

	private static final DataBindContext PROFILED = DataBindContext.builder().profile("p").build();

	private static final String DIFFER = "refused: Field '%s' already has Field annotation";

	private static final String UNIONS_DIFFER = "refused: Field '%s' has @Union annotations that differ";

	private static final String OBJECT = "refused: Unable to find a valid data conversion for class: class "
			+ "java.lang.Object";

	@Test
	void aCanonicalRecordReadsFieldFromItsAccessorAlone() {
		assertAll(
				described(PLAIN, HeaderField.class, "a:String"),
				described(PLAIN, HeaderFieldExplicitAccessor.class, "x:String"),
				described(PLAIN, AccessorFieldBesideHeader.class, "b:String"),
				described(PLAIN, AccessorRequiredBesideHeader.class, "x!:String"),
				described(PLAIN, AccessorBridgeBesideHeader.class, "x:String"),
				described(PLAIN, ExplicitCanonicalParameterField.class, "x:String"),
				described(PLAIN, ExplicitCanonicalParameterBesideAccessor.class, "b:String"),
				described(PLAIN, ProfiledAccessorField.class, "acc:String, y:String"),
				described(PLAIN, ProfiledAccessorBesideParameterField.class, "acc!:String, y:String"),
				described(PLAIN, ProfiledParameterBridge.class, "x:Integer, y:String"));
	}

	@Test
	void aCanonicalRecordReadsUnionFromItsAccessorAlone() {
		assertAll(
				described(PLAIN, HeaderUnion.class, "tag:union(String|Integer)"),
				described(PLAIN, HeaderUnionExplicitAccessor.class, OBJECT),
				described(PLAIN, AccessorUnionBesideHeader.class, "tag:union(String)"),
				described(PLAIN, ExplicitCanonicalParameterUnion.class, OBJECT),
				described(PLAIN, ProfiledHeaderUnion.class, "tag:union(String|Integer), y:String"));
	}

	@Test
	void aCanonicalRecordReadsUnboundFromItsAccessorAndParameter() {
		assertAll(
				described(PLAIN, UnboundAccessor.class, "name:String"),
				described(PLAIN, UnboundExplicitCanonicalParameter.class, "name:String"),
				described(PLAIN, ProfiledUnboundAccessor.class, "name:String, tier:String"));
	}

	@Test
	void aProfiledRecordReadsFieldFromItsParameterAlone() {
		assertAll(
				described(PROFILED, ProfiledFieldsBesideParameterField.class, "b:String"),
				described(PROFILED, ProfiledParameterField.class, "x!:String"),
				described(PROFILED, ProfiledParameterBridge.class, "x:String"),
				described(PROFILED, ProfiledAccessorField.class, "x:String"),
				described(PROFILED, ProfiledAccessorBesideParameterField.class, "x:String"));
	}

	@Test
	void aProfiledRecordReadsNoUnion() {
		assertAll(
				described(PROFILED, ProfiledHeaderUnion.class, OBJECT),
				described(PROFILED, ProfiledParameterUnion.class, OBJECT));
	}

	@Test
	void aProfiledRecordReadsUnboundFromItsAccessor() {
		assertAll(described(PROFILED, ProfiledUnboundAccessor.class, "name:String"));
	}

	@Test
	void aConstructorClassReadsFieldFromItsStoredFieldParameterAndAccessor() {
		assertAll(
				described(PLAIN, ConstructorStoredField.class, "a:String"),
				described(PLAIN, ConstructorParameter.class, "a:String"),
				described(PLAIN, ConstructorAccessor.class, "a:String"),
				described(PLAIN, ConstructorAgreeingMembers.class, "a:String"),
				described(PLAIN, ConstructorNamedAccessor.class, "x:String"));
	}

	@Test
	void aConstructorClassRefusesMembersWhoseFieldsDiffer() {
		assertAll(
				described(PLAIN, ConstructorFieldBesideParameter.class, DIFFER.formatted("x")),
				described(PLAIN, ConstructorFieldBesideAccessor.class, DIFFER.formatted("x")),
				described(PLAIN, ConstructorParameterBesideAccessor.class, DIFFER.formatted("x")),
				described(PLAIN, ConstructorRequiredDiffers.class, DIFFER.formatted("x")),
				described(PLAIN, ConstructorBridgeOnOne.class, DIFFER.formatted("x")),
				described(PLAIN, ConstructorNamedAccessorRequired.class, DIFFER.formatted("x")),
				described(PLAIN, ConstructorComputedIntoAnnotatedField.class, DIFFER.formatted("x")));
	}

	@Test
	void aConstructorClassRefusesDifferingFieldsBeforeAnUnstoredParameter() {
		assertAll(described(PLAIN, ConstructorDisagreementBesideUnstoredParameter.class, DIFFER.formatted("x")));
	}

	/** The parameter's @Field reaches the first field storing it only, so the second's differing one refuses nothing. */
	@Test
	void aParameterFieldReachesTheFirstComponentStoringIt() {
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
				described(PLAIN, ConstructorUnionFieldBesideParameter.class, UNIONS_DIFFER.formatted("tag")),
				described(PLAIN, ConstructorUnionFieldBesideAccessor.class, UNIONS_DIFFER.formatted("tag")),
				described(PLAIN, ConstructorUnionUnstoredParameter.class, "refused: "
						+ ConstructorUnionUnstoredParameter.class.getName() + "'s constructor does not assign its "
						+ "@Union parameter 0 unchanged to a field, and no @Field names it"));
	}

	@Test
	void aPublicFieldReadsFieldAndNoUnion() {
		assertAll(
				described(PLAIN, PublicFieldNamed.class, "m:String set, z:String set"),
				described(PLAIN, PublicFieldUnion.class, OBJECT));
	}

	@Test
	void aPairReadsFieldFromItsGetterSetterAndPrivateField() {
		assertAll(
				described(PLAIN, PairGetter.class, "a:String set"),
				described(PLAIN, PairSetter.class, "a:String set"),
				described(PLAIN, PairPrivateField.class, "a:String set"),
				described(PLAIN, PairAgreeingMembers.class, "a:String set"),
				described(PLAIN, PairGetterBesideSetter.class, DIFFER.formatted("x")),
				described(PLAIN, PairGetterBesidePrivateField.class, DIFFER.formatted("x")),
				described(PLAIN, PairSetterBesidePrivateField.class, DIFFER.formatted("x")),
				described(PLAIN, PairRequiredDiffers.class, DIFFER.formatted("x")),
				described(PLAIN, PairBridgeOnOne.class, DIFFER.formatted("x")));
	}

	@Test
	void aPairRefusesAPrivateFieldOfNoComponentAfterDifferingFields() {
		assertAll(
				described(PLAIN, PairOrphanPrivateField.class,
						"refused: Could not find matching getter/setter for field 'orphan'"),
				described(PLAIN, PairDisagreementBesideOrphan.class, DIFFER.formatted("x")));
	}

	@Test
	void aPairReadsNoUnion() {
		assertAll(
				described(PLAIN, PairGetterUnion.class, OBJECT),
				described(PLAIN, PairPrivateFieldUnion.class, OBJECT));
	}

	/** The merge carries the setter across and its @Unbound, but not its @Field or @Union. */
	@Test
	void aSetterMergedIntoAConstructorComponentGivesItNoFieldOrUnion() {
		assertAll(
				described(PLAIN, MergedSetterField.class, "count:String set"),
				described(PLAIN, MergedSetterRequired.class, "count:String set"),
				described(PLAIN, MergedSetterBesideStoredField.class, "a:String set"),
				described(PLAIN, MergedSetterUnion.class, OBJECT),
				described(PLAIN, MergedSetterUnbound.class, "name:String"));
	}

	/** The pair's own members are compared before the merge, though the merge drops what they carry. */
	@Test
	void aMergedPairRefusesGetterAndSetterFieldsThatDiffer() {
		assertAll(
				described(PLAIN, MergedGetterField.class, "g:String set"),
				described(PLAIN, MergedGetterBesideSetter.class, DIFFER.formatted("count")));
	}

	@Test
	void aPublicFieldMergedIntoAConstructorComponentKeepsWhatTheConstructorAnalysisRead() {
		assertAll(
				described(PLAIN, MergedPublicField.class, "a:String set"),
				described(PLAIN, MergedPublicFieldUnion.class, "tag:union(String|Integer) set"));
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

	private static String describe(DataClassField field) {
		DataClass data = field.dataClass();
		String bound = data instanceof DataClassUnion union ? Stream.of(union.memberTypes())
				.map(Class::getSimpleName).collect(Collectors.joining("|", "union(", ")"))
				: data.dataClass().getSimpleName();
		return field.name() + (field.isRequired() ? "!" : "") + ":" + bound
				+ (field.setter().isPresent() ? " set" : "");
	}
}
