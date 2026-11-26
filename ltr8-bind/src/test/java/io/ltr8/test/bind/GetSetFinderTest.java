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
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.GetSetClasses.BoxedSetter;
import io.ltr8.test.data.GetSetClasses.FinalFieldBesideMutableField;
import io.ltr8.test.data.GetSetClasses.FinalFieldWithAccessor;
import io.ltr8.test.data.GetSetClasses.FinalFieldWithoutAccessor;
import io.ltr8.test.data.GetSetClasses.InlineFinalField;
import io.ltr8.test.data.GetSetClasses.ListPair;
import io.ltr8.test.data.GetSetClasses.MutableFieldBoundByConstructor;
import io.ltr8.test.data.GetSetClasses.MutableFields;
import io.ltr8.test.data.GetSetClasses.OptionalField;
import io.ltr8.test.data.GetSetClasses.OptionalPair;
import io.ltr8.test.data.GetSetClasses.OverloadedSetter;
import io.ltr8.test.data.GetSetClasses.PackagePrivatePair;
import io.ltr8.test.data.GetSetClasses.PairNamedInAnotherCase;
import io.ltr8.test.data.GetSetClasses.PrivateGetterPublicSetter;
import io.ltr8.test.data.GetSetClasses.PrivatePairAlone;
import io.ltr8.test.data.GetSetClasses.PrivatePairBesidePublicField;
import io.ltr8.test.data.GetSetClasses.PrivatePairOverConstructorName;
import io.ltr8.test.data.GetSetClasses.PrivateSetterOverConstructorName;
import io.ltr8.test.data.GetSetClasses.ProtectedPair;
import io.ltr8.test.data.GetSetClasses.PublicGetterPrivateSetter;
import io.ltr8.test.data.GetSetClasses.PublicPair;
import io.ltr8.test.data.GetSetClasses.PublicPairOverConstructorName;
import io.ltr8.test.data.GetSetClasses.Relabelled;
import io.ltr8.test.data.GetSetClasses.Retagged;
import io.ltr8.test.data.GetSetClasses.StaticField;
import io.ltr8.test.data.GetSetClasses.StaticPair;
import io.ltr8.test.data.GetSetClasses.TextHolder;
import io.ltr8.test.data.GetSetClasses.TwoArgumentSetter;
import io.ltr8.test.data.GetSetClasses.WiderSetter;
import io.ltr8.test.data.GetSetClasses.WritesGrandInherited;
import io.ltr8.test.data.GetSetClasses.WritesInherited;
import io.ltr8.test.data.GetSetClasses.WritesInheritedBesideAccessor;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which public fields and getter/setter pairs of a hand-written {@code @Record} class are components, and how each
 * is read and written.
 */
class GetSetFinderTest {

	private final DataBindContext context = DataBindContext.builder().build();

	// --- public fields

	@Test
	void aPublicFinalFieldTheConstructorBindsIsReadThroughItsAccessorAndWrittenThroughTheConstructor()
			throws Throwable {
		DataClassRecord record = record(FinalFieldWithAccessor.class);
		assertEquals(List.of("name:String"), shape(record));
		assertReadThroughMethod(record.fields()[0], "name");
		FinalFieldWithAccessor value = new FinalFieldWithAccessor("anchor");
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (FinalFieldWithAccessor copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
		}
	}

	@Test
	void aPublicFinalFieldTheConstructorBindsSitsBesideAPublicMutableField() throws Throwable {
		DataClassRecord record = record(FinalFieldBesideMutableField.class);
		assertEquals(List.of("name:String", "count:int set"), shape(record));
		FinalFieldBesideMutableField value = new FinalFieldBesideMutableField("anchor");
		value.count = 3;
		assertEquals(List.of("anchor", 3), Arrays.asList(new ArrayMapper(context).toArray(value)));
		for (FinalFieldBesideMutableField copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals(3, copy.count);
		}
	}

	@Test
	void aPublicFinalFieldTheConstructorBindsWithoutAnAccessorIsRefusedByTheConstructorAnalysis() {
		assertEquals("Failed to match immutable field accessor for class: " + FinalFieldWithoutAccessor.class
				+ ". Add @Field annotations to assist field 'name'", message(FinalFieldWithoutAccessor.class));
	}

	@Test
	void aPublicFinalFieldNoConstructorBindsIsNoComponent() throws Throwable {
		DataClassRecord record = record(InlineFinalField.class);
		assertEquals(List.of("name:String set"), shape(record));
		InlineFinalField value = new InlineFinalField();
		value.name = "anchor";
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (InlineFinalField copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals("fixed", copy.fixed);
		}
	}

	@Test
	void publicMutableFieldsAreReadAndWrittenThroughTheFieldsInNameOrder() throws Throwable {
		DataClassRecord record = record(MutableFields.class);
		assertEquals(List.of("count:int set", "name:String set"), shape(record));
		MutableFields value = new MutableFields();
		value.name = "anchor";
		value.count = 3;
		for (MutableFields copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals(3, copy.count);
		}
	}

	@Test
	void aPublicMutableFieldTheConstructorBindsIsWrittenThroughTheConstructor() throws Throwable {
		DataClassRecord record = record(MutableFieldBoundByConstructor.class);
		assertEquals(List.of("name:String set"), shape(record));
		for (MutableFieldBoundByConstructor copy : roundTrips(new MutableFieldBoundByConstructor("anchor"))) {
			assertEquals("anchor", copy.name);
		}
	}

	@Test
	void aPublicOptionalFieldIsWrittenThroughTheFieldWhetherPresentOrAbsent() throws Throwable {
		DataClassRecord record = record(OptionalField.class);
		assertEquals(List.of("note:String set"), shape(record));
		MethodHandle setter = record.fields()[0].setter().orElseThrow();
		assertEquals(MethodType.methodType(void.class, OptionalField.class, String.class), setter.type());
		OptionalField written = new OptionalField();
		record.fields()[0].set(written, "anchor");
		assertEquals(Optional.of("anchor"), written.note);
		record.fields()[0].set(written, null);
		assertEquals(Optional.empty(), written.note);

		OptionalField present = new OptionalField();
		present.note = Optional.of("anchor");
		assertEquals(Map.of("note", "anchor"), new MapMapper(context).toMap(present));
		for (OptionalField copy : roundTrips(present)) {
			assertEquals(Optional.of("anchor"), copy.note);
		}
		OptionalField absent = new OptionalField();
		assertEquals(Map.of(), new MapMapper(context).toMap(absent));
		for (OptionalField copy : roundTrips(absent)) {
			assertEquals(Optional.empty(), copy.note);
		}
	}

	@Test
	void aStaticFieldIsNoComponent() throws Throwable {
		DataClassRecord record = record(StaticField.class);
		assertEquals(List.of("name:String set"), shape(record));
		StaticField value = new StaticField();
		value.name = "anchor";
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
	}

	// --- getter/setter pairs

	@Test
	void aPublicPairIsReadThroughTheGetterAndWrittenThroughTheSetter() throws Throwable {
		DataClassRecord record = record(PublicPair.class);
		assertEquals(List.of("value:String set"), shape(record));
		assertReadThroughMethod(record.fields()[0], "getValue");
		PublicPair value = new PublicPair();
		value.setValue("anchor");
		for (PublicPair copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aListPairKeepsItsElementType() throws Throwable {
		DataClassRecord record = record(ListPair.class);
		assertEquals(List.of("items:List set"), shape(record));
		ListPair value = new ListPair();
		value.setItems(List.of("anchor", "chain"));
		for (ListPair copy : roundTrips(value)) {
			assertEquals(List.of("anchor", "chain"), copy.getItems());
		}
	}

	@Test
	void anOptionalPairIsWrittenThroughTheSetterWhetherPresentOrAbsent() throws Throwable {
		DataClassRecord record = record(OptionalPair.class);
		assertEquals(List.of("note:String set"), shape(record));
		assertEquals(MethodType.methodType(void.class, OptionalPair.class, String.class),
				record.fields()[0].setter().orElseThrow().type());
		OptionalPair present = new OptionalPair();
		present.setNote(Optional.of("anchor"));
		assertEquals(Map.of("note", "anchor"), new MapMapper(context).toMap(present));
		for (OptionalPair copy : roundTrips(present)) {
			assertEquals(Optional.of("anchor"), copy.getNote());
		}
		OptionalPair absent = new OptionalPair();
		assertEquals(Map.of(), new MapMapper(context).toMap(absent));
		for (OptionalPair copy : roundTrips(absent)) {
			assertEquals(Optional.empty(), copy.getNote());
		}
	}

	@Test
	void aPrivatePairIsNoComponentAndItsValueIsNotCarried() throws Throwable {
		DataClassRecord record = record(PrivatePairBesidePublicField.class);
		assertEquals(List.of("name:String set"), shape(record));
		PrivatePairBesidePublicField value = new PrivatePairBesidePublicField();
		value.name = "anchor";
		value.secret("kept back");
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (PrivatePairBesidePublicField copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertNull(copy.secret());
		}
	}

	@Test
	void aPackagePrivateOrProtectedPairIsNoComponent() throws Throwable {
		assertEquals(List.of("name:String set"), shape(record(PackagePrivatePair.class)));
		assertEquals(List.of("name:String set"), shape(record(ProtectedPair.class)));
		PackagePrivatePair packagePrivate = new PackagePrivatePair();
		packagePrivate.name = "anchor";
		for (PackagePrivatePair copy : roundTrips(packagePrivate)) {
			assertEquals("anchor", copy.name);
		}
		ProtectedPair protectedPair = new ProtectedPair();
		protectedPair.name = "anchor";
		for (ProtectedPair copy : roundTrips(protectedPair)) {
			assertEquals("anchor", copy.name);
		}
	}

	@Test
	void aPairWithEitherHalfNotPublicIsNoComponent() throws Throwable {
		assertEquals(List.of("name:String set"), shape(record(PublicGetterPrivateSetter.class)));
		assertEquals(List.of("name:String set"), shape(record(PrivateGetterPublicSetter.class)));
		PublicGetterPrivateSetter readOnly = new PublicGetterPrivateSetter();
		readOnly.name = "anchor";
		for (PublicGetterPrivateSetter copy : roundTrips(readOnly)) {
			assertEquals("anchor", copy.name);
			assertNull(copy.getSecret());
		}
		PrivateGetterPublicSetter writeOnly = new PrivateGetterPublicSetter();
		writeOnly.name = "anchor";
		writeOnly.setSecret("kept back");
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(writeOnly));
		for (PrivateGetterPublicSetter copy : roundTrips(writeOnly)) {
			assertEquals("anchor", copy.name);
		}
	}

	@Test
	void aClassWhoseOnlyPairIsPrivateHasNoComponents() throws Throwable {
		DataClassRecord record = record(PrivatePairAlone.class);
		assertEquals(List.of(), shape(record));
		PrivatePairAlone value = new PrivatePairAlone();
		assertEquals(Map.of(), new MapMapper(context).toMap(value));
		assertEquals(0, new ArrayMapper(context).toArray(value).length);
		for (PrivatePairAlone copy : roundTrips(value)) {
			assertInstanceOf(PrivatePairAlone.class, copy);
		}
	}

	@Test
	void aPrivateSetterBesideTheConstructorsAccessorLeavesTheConstructorsComponent() throws Throwable {
		DataClassRecord record = record(PrivateSetterOverConstructorName.class);
		assertEquals(List.of("name:String"), shape(record));
		assertReadThroughMethod(record.fields()[0], "getName");
		for (PrivateSetterOverConstructorName copy : roundTrips(new PrivateSetterOverConstructorName("anchor"))) {
			assertEquals("anchor", copy.getName());
		}
	}

	@Test
	void aPublicPairOverTheConstructorsFieldIsTheConstructorsComponentWrittenThroughTheSetter() throws Throwable {
		DataClassRecord record = record(PublicPairOverConstructorName.class);
		assertEquals(List.of("name:String set"), shape(record));
		assertReadThroughMethod(record.fields()[0], "getName");
		PublicPairOverConstructorName written = new PublicPairOverConstructorName("anchor");
		record.fields()[0].set(written, "buoy");
		assertEquals("buoy", written.getName());
		for (PublicPairOverConstructorName copy : roundTrips(new PublicPairOverConstructorName("anchor"))) {
			assertEquals("anchor", copy.getName());
		}
	}

	@Test
	void aPairWhoseNameDiffersFromTheConstructorsOnlyInCaseIsAComponentOfItsOwn() throws Throwable {
		DataClassRecord record = record(PairNamedInAnotherCase.class);
		assertEquals(List.of("url:String", "uRL:String set"), shape(record));
		PairNamedInAnotherCase value = new PairNamedInAnotherCase("https://example.org/");
		value.setURL("next");
		for (PairNamedInAnotherCase copy : roundTrips(value)) {
			assertEquals("https://example.org/", copy.url());
			assertEquals("next", copy.getURL());
		}
	}

	@Test
	void aSubclassPairShadowingASuperClassComponentIsAComponentOfItsOwn() throws Throwable {
		DataClassRecord record = record(Relabelled.class);
		assertEquals(List.of("name:String", "alias:String set"), shape(record));
		Relabelled value = new Relabelled("anchor");
		value.setName("buoy");
		assertEquals(Map.of("name", "anchor", "alias", "buoy"), new MapMapper(context).toMap(value));
		for (Relabelled copy : roundTrips(value)) {
			assertEquals("anchor", copy.name());
			assertEquals("buoy", copy.getName());
		}
	}

	@Test
	void aPairWhoseGetterIsTheAccessorOfASuperClassComponentWritesThatComponent() throws Throwable {
		DataClassRecord record = record(WritesInherited.class);
		assertEquals(List.of("name:String set"), shape(record));
		assertReadThroughMethod(record.fields()[0], "getName");
		WritesInherited written = new WritesInherited("anchor");
		record.fields()[0].set(written, "buoy");
		assertEquals("buoy", written.getName());
		assertEquals("buoy", record.fields()[0].get(written));
		for (WritesInherited copy : roundTrips(new WritesInherited("anchor"))) {
			assertEquals("anchor", copy.getName());
		}
	}

	@Test
	void aPairBesideTheSuperClassesOwnAccessorWritesTheComponentReadThroughThatAccessor() throws Throwable {
		DataClassRecord record = record(WritesInheritedBesideAccessor.class);
		assertEquals(List.of("name:String set"), shape(record));
		// The analysis reads through the super class's name(), matched after getName(): a handle on a super-class
		// method is adapted to take the described class, so it is no direct handle, as one on getName() would be.
		assertThrows(IllegalArgumentException.class,
				() -> MethodHandles.lookup().revealDirect(record.fields()[0].accessor()));
		WritesInheritedBesideAccessor written = new WritesInheritedBesideAccessor("anchor");
		record.fields()[0].set(written, "buoy");
		assertEquals("buoy", written.name());
		assertEquals("buoy", record.fields()[0].get(written));
		for (WritesInheritedBesideAccessor copy : roundTrips(new WritesInheritedBesideAccessor("anchor"))) {
			assertEquals("anchor", copy.name());
		}
	}

	@Test
	void aPairWhoseGetterIsTheAccessorOfAComponentTwoClassesUpWritesThatComponent() throws Throwable {
		DataClassRecord record = record(WritesGrandInherited.class);
		assertEquals(List.of("name:String set"), shape(record));
		assertReadThroughMethod(record.fields()[0], "getName");
		WritesGrandInherited written = new WritesGrandInherited("anchor");
		record.fields()[0].set(written, "buoy");
		assertEquals("buoy", written.getName());
		for (WritesGrandInherited copy : roundTrips(new WritesGrandInherited("anchor"))) {
			assertEquals("anchor", copy.getName());
		}
	}

	@Test
	void anInheritedPublicFieldTheSuperConstructorBindsIsThatConstructorsComponent() throws Throwable {
		DataClassRecord record = record(Retagged.class);
		assertEquals(List.of("tag:String set"), shape(record));
		for (Retagged copy : roundTrips(new Retagged("anchor"))) {
			assertEquals("anchor", copy.tag);
		}
	}

	@Test
	void aPrivateGetterTheConstructorAnalysisMatchesIsRefusedByThatAnalysis() {
		DataBindException refused = refusal(PrivatePairOverConstructorName.class);
		assertEquals("Failed to access class", refused.getMessage());
		IllegalAccessException cause = assertInstanceOf(IllegalAccessException.class, refused.getCause());
		assertTrue(cause.getMessage().startsWith("member is private: "
				+ PrivatePairOverConstructorName.class.getName() + ".getName"), cause.getMessage());
	}

	@Test
	void aStaticPairIsNoComponent() throws Throwable {
		DataClassRecord record = record(StaticPair.class);
		assertEquals(List.of("name:String set"), shape(record));
		StaticPair value = new StaticPair();
		value.name = "anchor";
		for (StaticPair copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
		}
	}

	// --- which setter pairs with a getter

	@Test
	void anOverloadedSetterPairsOnlyItsOverloadTakingTheGettersType() throws Throwable {
		DataClassRecord record = record(OverloadedSetter.class);
		assertEquals(List.of("value:String set"), shape(record));
		assertEquals(MethodType.methodType(void.class, OverloadedSetter.class, String.class),
				record.fields()[0].setter().orElseThrow().type());
		OverloadedSetter value = new OverloadedSetter();
		value.setValue("anchor");
		for (OverloadedSetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aPairOverridingAGenericPairIsOneComponentAtTheOverridingType() throws Throwable {
		DataClassRecord record = record(TextHolder.class);
		assertEquals(List.of("value:String set"), shape(record));
		TextHolder value = new TextHolder();
		value.setValue("anchor");
		for (TextHolder copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aTwoArgumentSetterPairsWithNoGetter() throws Throwable {
		DataClassRecord record = record(TwoArgumentSetter.class);
		assertEquals(List.of("name:String set"), shape(record));
		TwoArgumentSetter value = new TwoArgumentSetter();
		value.name = "anchor";
		value.setValue("kept back", 1);
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (TwoArgumentSetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertNull(copy.getValue());
		}
	}

	@Test
	void aSetterTakingAWiderTypeThanItsGetterReturnsPairsWithNoGetter() throws Throwable {
		DataClassRecord record = record(WiderSetter.class);
		assertEquals(List.of("name:String set"), shape(record));
		WiderSetter value = new WiderSetter();
		value.name = "anchor";
		value.setValue("kept back");
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (WiderSetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertNull(copy.getValue());
		}
	}

	@Test
	void aSetterTakingTheBoxOfItsGettersPrimitivePairsWithNoGetter() throws Throwable {
		DataClassRecord record = record(BoxedSetter.class);
		assertEquals(List.of("name:String set"), shape(record));
		BoxedSetter value = new BoxedSetter();
		value.name = "anchor";
		value.setCount(3);
		for (BoxedSetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals(0, copy.getCount());
		}
	}

	// --- a member the public lookup cannot reach

	static class HiddenWithPublicField {

		public String name;

		@Record
		public HiddenWithPublicField() {
		}
	}

	@Test
	void aPublicFieldOfAClassThePublicLookupCannotReachIsRefusedNamingTheFieldWithTheCauseKept() {
		DataBindException refused = refusal(HiddenWithPublicField.class);
		String message = refused.getMessage();
		assertTrue(message.startsWith(HiddenWithPublicField.class.getName()
				+ " is bound through a member that is not accessible: class is not public: "
				+ HiddenWithPublicField.class.getName() + ".name"), message);
		IllegalAccessException cause = assertInstanceOf(IllegalAccessException.class, refused.getCause());
		assertTrue(message.endsWith(cause.getMessage()), message);
	}

	// --- helpers

	private DataClassRecord record(Class<?> type) throws DataBindException {
		return assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
	}

	/** Each field as {@code name:Type}, with {@code set} where the descriptor writes it through a setter. */
	private static List<String> shape(DataClassRecord record) {
		return Arrays.stream(record.fields())
				.map(field -> field.name() + ":" + field.type().getSimpleName()
						+ (field.setter().isPresent() ? " set" : ""))
				.toList();
	}

	private static void assertReadThroughMethod(DataClassField field, String method) {
		MethodHandleInfo read = MethodHandles.lookup().revealDirect(field.accessor());
		assertEquals(MethodHandleInfo.REF_invokeVirtual, read.getReferenceKind(), field.name());
		assertEquals(method, read.getName(), field.name());
	}

	/** {@code value} written and read back through an {@link ArrayMapper}, then through a {@link MapMapper}. */
	private <T> List<T> roundTrips(T value) throws Throwable {
		@SuppressWarnings("unchecked")
		Class<T> type = (Class<T>) value.getClass();
		ArrayMapper array = new ArrayMapper(context);
		MapMapper map = new MapMapper(context);
		return List.of(array.toObject(type, array.toArray(value)), type.cast(map.toObject(type, map.toMap(value))));
	}

	private String message(Class<?> type) {
		return refusal(type).getMessage();
	}

	private DataBindException refusal(Class<?> type) {
		DataBindException refused = assertThrows(DataBindException.class, () -> context.getDescriptor(type));
		assertTrue(refused.getMessage() != null, type.getName());
		return refused;
	}
}
