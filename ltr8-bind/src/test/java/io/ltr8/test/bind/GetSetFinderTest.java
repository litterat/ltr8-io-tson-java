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
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.GetSetClasses.BoxedSetter;
import io.ltr8.test.data.GetSetClasses.CountFields;
import io.ltr8.test.data.GetSetClasses.DefaultPair;
import io.ltr8.test.data.GetSetClasses.FieldBesideInheritedPair;
import io.ltr8.test.data.GetSetClasses.FinalFieldBesideMutableField;
import io.ltr8.test.data.GetSetClasses.FinalFieldWithAccessor;
import io.ltr8.test.data.GetSetClasses.FinalFieldWithoutAccessor;
import io.ltr8.test.data.GetSetClasses.FluentSetter;
import io.ltr8.test.data.GetSetClasses.GetterOverride;
import io.ltr8.test.data.GetSetClasses.HidesNamedField;
import io.ltr8.test.data.GetSetClasses.HoldsOpenCounts;
import io.ltr8.test.data.GetSetClasses.InheritsFluentSetter;
import io.ltr8.test.data.GetSetClasses.InheritsHiddenField;
import io.ltr8.test.data.GetSetClasses.InheritsHiddenGenericPair;
import io.ltr8.test.data.GetSetClasses.InheritsHiddenPair;
import io.ltr8.test.data.GetSetClasses.InheritsNamedByConstructor;
import io.ltr8.test.data.GetSetClasses.InlineFinalField;
import io.ltr8.test.data.GetSetClasses.LabelledFieldBesideInheritedPair;
import io.ltr8.test.data.GetSetClasses.LabelledFieldBesideOverriddenGetter;
import io.ltr8.test.data.GetSetClasses.ListPair;
import io.ltr8.test.data.GetSetClasses.MutableFieldBoundByConstructor;
import io.ltr8.test.data.GetSetClasses.MutableFields;
import io.ltr8.test.data.GetSetClasses.Named;
import io.ltr8.test.data.GetSetClasses.NamedFieldBase;
import io.ltr8.test.data.GetSetClasses.NestedArgumentPair;
import io.ltr8.test.data.GetSetClasses.NumberReadableWritten;
import io.ltr8.test.data.GetSetClasses.OptionalField;
import io.ltr8.test.data.GetSetClasses.OptionalPair;
import io.ltr8.test.data.GetSetClasses.OtherElementSetter;
import io.ltr8.test.data.GetSetClasses.OpenCounts;
import io.ltr8.test.data.GetSetClasses.OtherNestedArgumentSetter;
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
import io.ltr8.test.data.GetSetClasses.RawBox;
import io.ltr8.test.data.GetSetClasses.RawFields;
import io.ltr8.test.data.GetSetClasses.RawSetter;
import io.ltr8.test.data.GetSetClasses.RawValue;
import io.ltr8.test.data.GetSetClasses.ReadableWritten;
import io.ltr8.test.data.GetSetClasses.Relabelled;
import io.ltr8.test.data.GetSetClasses.RenamedOverAliasedPair;
import io.ltr8.test.data.GetSetClasses.RenamedOverPair;
import io.ltr8.test.data.GetSetClasses.ReportingSetter;
import io.ltr8.test.data.GetSetClasses.Retagged;
import io.ltr8.test.data.GetSetClasses.StaticField;
import io.ltr8.test.data.GetSetClasses.StaticPair;
import io.ltr8.test.data.GetSetClasses.SwappingSetter;
import io.ltr8.test.data.GetSetClasses.TextArray;
import io.ltr8.test.data.GetSetClasses.TextArrayFields;
import io.ltr8.test.data.GetSetClasses.TextArrayGetter;
import io.ltr8.test.data.GetSetClasses.TextBox;
import io.ltr8.test.data.GetSetClasses.TextFields;
import io.ltr8.test.data.GetSetClasses.TextHolder;
import io.ltr8.test.data.GetSetClasses.TextList;
import io.ltr8.test.data.GetSetClasses.TextListArrayGetter;
import io.ltr8.test.data.GetSetClasses.TextListFields;
import io.ltr8.test.data.GetSetClasses.TextPassed;
import io.ltr8.test.data.GetSetClasses.TextReadableWritten;
import io.ltr8.test.data.GetSetClasses.TextValue;
import io.ltr8.test.data.GetSetClasses.TextWildcard;
import io.ltr8.test.data.GetSetClasses.TextWildcardFields;
import io.ltr8.test.data.GetSetClasses.TextWildcardGetter;
import io.ltr8.test.data.GetSetClasses.TwoArgumentSetter;
import io.ltr8.test.data.GetSetClasses.UnboundFieldBesideInheritedPair;
import io.ltr8.test.data.GetSetClasses.UnboundFieldBesideInheritedPublicField;
import io.ltr8.test.data.GetSetClasses.WiderSetter;
import io.ltr8.test.data.GetSetClasses.WritableRead;
import io.ltr8.test.data.GetSetClasses.WritesGrandInherited;
import io.ltr8.test.data.GetSetClasses.WritesHiddenGetter;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
	void aPublicFinalFieldTheConstructorBindsWithoutAnAccessorIsOneComponentReadFromTheField() throws Throwable {
		DataClassRecord record = record(FinalFieldWithoutAccessor.class);
		assertEquals(List.of("name:String"), shape(record));
		MethodHandleInfo read = MethodHandles.lookup().revealDirect(record.fields()[0].accessor());
		assertEquals(MethodHandleInfo.REF_getField, read.getReferenceKind());
		assertEquals("name", read.getName());
		for (FinalFieldWithoutAccessor copy : roundTrips(new FinalFieldWithoutAccessor("anchor"))) {
			assertEquals("anchor", copy.name);
		}
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
	void aPrivatePairBesideTheConstructorsAccessorLeavesTheConstructorsComponent() throws Throwable {
		DataClassRecord record = record(PrivatePairOverConstructorName.class);
		assertEquals(List.of("name:String"), shape(record));
		assertReadThroughMethod(record.fields()[0], "name");
		for (PrivatePairOverConstructorName copy : roundTrips(new PrivatePairOverConstructorName("anchor"))) {
			assertEquals("anchor", copy.name());
		}
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

	// --- pairs a super class declares

	@Test
	void aPairASuperClassDeclaresIsAComponent() throws Throwable {
		DataClassRecord record = record(Named.class);
		assertEquals(List.of("name:String set"), shape(record));
		Named value = new Named();
		value.setName("anchor");
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (Named copy : roundTrips(value)) {
			assertEquals("anchor", copy.getName());
		}
	}

	@Test
	void aGenericPairASuperClassDeclaresIsAComponentAtTheTypeTheSubclassBinds() throws Throwable {
		DataClassRecord record = record(TextBox.class);
		assertEquals(List.of("value:String set"), shape(record));
		assertEquals(MethodType.methodType(String.class, TextBox.class), record.fields()[0].accessor().type());
		TextBox value = new TextBox();
		value.setValue("anchor");
		for (TextBox copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aGenericPairOfASuperClassExtendedRawIsAtItsErasure() {
		assertEquals("Unable to find a valid data conversion for class: class java.lang.Object",
				message(RawBox.class));
	}

	@Test
	void aGenericPairIsBoundThroughAGenericClassBetween() throws Throwable {
		DataClassRecord record = record(TextPassed.class);
		assertEquals(List.of("value:String set"), shape(record));
		TextPassed value = new TextPassed();
		value.setValue("anchor");
		for (TextPassed copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aGenericListPairASuperClassDeclaresKeepsTheElementTypeTheSubclassBinds() throws Throwable {
		DataClassRecord record = record(TextList.class);
		assertEquals(List.of("items:List set"), shape(record));
		assertEquals(String.class, assertInstanceOf(DataClassArray.class, record.fields()[0].dataClass())
				.arrayDataClass().typeClass());
		TextList value = new TextList();
		value.setItems(List.of("anchor", "chain"));
		for (TextList copy : roundTrips(value)) {
			assertEquals(List.of("anchor", "chain"), copy.getItems());
		}
	}

	@Test
	void aSuperClassPairWhoseGetterAloneIsOverriddenIsOneComponentAtTheOverridingType() throws Throwable {
		DataClassRecord record = record(GetterOverride.class);
		assertEquals(List.of("value:String set"), shape(record));
		assertReadThroughMethod(record.fields()[0], "getValue");
		GetterOverride value = new GetterOverride();
		value.setValue("anchor");
		for (GetterOverride copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aSetterPairsWithTheGetterItsSuperClassDeclares() throws Throwable {
		DataClassRecord record = record(ReadableWritten.class);
		assertEquals(List.of("value:String set"), shape(record));
		ReadableWritten value = new ReadableWritten();
		value.setValue("anchor");
		for (ReadableWritten copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aGetterPairsWithTheSetterItsSuperClassDeclares() throws Throwable {
		DataClassRecord record = record(WritableRead.class);
		assertEquals(List.of("value:String set"), shape(record));
		WritableRead value = new WritableRead();
		value.setValue("anchor");
		for (WritableRead copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aSetterPairsWithAGenericSuperClassGetterAtTheTypeTheSubclassBinds() throws Throwable {
		DataClassRecord record = record(TextReadableWritten.class);
		assertEquals(List.of("value:String set"), shape(record));
		TextReadableWritten value = new TextReadableWritten();
		value.setValue("anchor");
		for (TextReadableWritten copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aSetterTakingAnotherTypeThanTheBoundSuperClassGetterPairsWithNoGetter() throws Throwable {
		DataClassRecord record = record(NumberReadableWritten.class);
		assertEquals(List.of("name:String set"), shape(record));
		NumberReadableWritten value = new NumberReadableWritten();
		value.name = "anchor";
		value.setValue(3);
		for (NumberReadableWritten copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertNull(copy.getValue());
		}
	}

	@Test
	void anInheritedPairOverTheSuperConstructorsFieldIsThatConstructorsComponentWrittenThroughTheSetter()
			throws Throwable {
		DataClassRecord record = record(InheritsNamedByConstructor.class);
		assertEquals(List.of("name:String set"), shape(record));
		InheritsNamedByConstructor written = new InheritsNamedByConstructor("anchor");
		record.fields()[0].set(written, "buoy");
		assertEquals("buoy", written.getName());
		for (InheritsNamedByConstructor copy : roundTrips(new InheritsNamedByConstructor("anchor"))) {
			assertEquals("anchor", copy.getName());
		}
	}

	@Test
	void anInheritedPairTakingTheNameOfTheConstructorsOwnComponentIsRefused() {
		assertEquals(RenamedOverPair.class.getName() + " has two components named 'name', one the pair "
				+ "NamedBase.getName and NamedBase.setName it inherits; @Field on the pair names one apart",
				message(RenamedOverPair.class));
	}

	@Test
	void anInheritedPairTakingTheNameOfAPublicFieldIsRefused() {
		assertEquals(FieldBesideInheritedPair.class.getName() + " has two components named 'name', one the pair "
				+ "NamedBase.getName and NamedBase.setName it inherits; @Field on the pair names one apart",
				message(FieldBesideInheritedPair.class));
	}

	@Test
	void anInheritedPairRenamedApartFromTheConstructorsOwnComponentIsAComponentOfItsOwn() throws Throwable {
		DataClassRecord record = record(RenamedOverAliasedPair.class);
		assertEquals(List.of("name:String", "alias:String set"), shape(record));
		RenamedOverAliasedPair value = new RenamedOverAliasedPair("anchor");
		value.setName("buoy");
		assertEquals(Map.of("name", "anchor", "alias", "buoy"), new MapMapper(context).toMap(value));
		for (RenamedOverAliasedPair copy : roundTrips(value)) {
			assertEquals("anchor", copy.name());
			assertEquals("buoy", copy.getName());
		}
	}

	/** A private field of the described class belongs to no component a super class declares. */
	@Test
	void anUnboundPrivateFieldNamedAsAnInheritedPairLeavesThePairBound() throws Throwable {
		DataClassRecord record = record(UnboundFieldBesideInheritedPair.class);
		assertEquals(List.of("name:String set"), shape(record));
		UnboundFieldBesideInheritedPair value = new UnboundFieldBesideInheritedPair();
		value.setName("anchor");
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (UnboundFieldBesideInheritedPair copy : roundTrips(value)) {
			assertEquals("anchor", copy.getName());
		}
	}

	@Test
	void anUnboundPrivateFieldNamedAsAnInheritedPublicFieldLeavesTheFieldBound() throws Throwable {
		DataClassRecord record = record(UnboundFieldBesideInheritedPublicField.class);
		assertEquals(List.of("name:String set"), shape(record));
		UnboundFieldBesideInheritedPublicField value = new UnboundFieldBesideInheritedPublicField();
		((NamedFieldBase) value).name = "anchor";
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (UnboundFieldBesideInheritedPublicField copy : roundTrips(value)) {
			assertEquals("anchor", ((NamedFieldBase) copy).name);
		}
	}

	@Test
	void aPrivateFieldNamedAsAnInheritedPairWithFieldIsRefusedAsMatchingNoPair() {
		assertEquals("Could not find matching getter/setter for field 'name'",
				message(LabelledFieldBesideInheritedPair.class));
	}

	/** A pair is declared by the class declaring its getter. */
	@Test
	void aPrivateFieldNamedAsAPairWhoseGetterTheClassOverridesNamesThePair() throws Throwable {
		DataClassRecord record = record(LabelledFieldBesideOverriddenGetter.class);
		assertEquals(List.of("label:String set"), shape(record));
		LabelledFieldBesideOverriddenGetter value = new LabelledFieldBesideOverriddenGetter();
		value.setName("anchor");
		assertEquals(Map.of("label", "anchor"), new MapMapper(context).toMap(value));
		for (LabelledFieldBesideOverriddenGetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.getName());
		}
	}

	@Test
	void aPairOfAnInterfacesDefaultMethodsIsNoComponent() throws Throwable {
		DataClassRecord record = record(DefaultPair.class);
		assertEquals(List.of("name:String set"), shape(record));
		DefaultPair value = new DefaultPair();
		value.name = "anchor";
		value.setLabel("kept back");
		for (DefaultPair copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertNull(copy.getLabel());
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
	void aSetterReturningItsObjectIsWrittenDroppingWhatItReturns() throws Throwable {
		DataClassRecord record = record(FluentSetter.class);
		assertEquals(List.of("value:String set"), shape(record));
		assertEquals(MethodType.methodType(void.class, FluentSetter.class, String.class),
				record.fields()[0].setter().orElseThrow().type());
		FluentSetter written = new FluentSetter();
		record.fields()[0].set(written, "buoy");
		assertEquals("buoy", written.getValue());
		for (FluentSetter copy : roundTrips(new FluentSetter().setValue("anchor"))) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aSetterReturningTheOldValueIsWrittenDroppingWhatItReturns() throws Throwable {
		DataClassRecord record = record(SwappingSetter.class);
		assertEquals(List.of("value:String set"), shape(record));
		SwappingSetter value = new SwappingSetter();
		value.setValue("anchor");
		for (SwappingSetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aSetterReturningAPrimitiveIsWrittenDroppingWhatItReturns() throws Throwable {
		DataClassRecord record = record(ReportingSetter.class);
		assertEquals(List.of("count:int set"), shape(record));
		ReportingSetter value = new ReportingSetter();
		value.setCount(3);
		for (ReportingSetter copy : roundTrips(value)) {
			assertEquals(3, copy.getCount());
		}
	}

	@Test
	void anInheritedSetterReturningItsSuperClassIsWrittenDroppingWhatItReturns() throws Throwable {
		DataClassRecord record = record(InheritsFluentSetter.class);
		assertEquals(List.of("value:String set"), shape(record));
		assertEquals(MethodType.methodType(void.class, InheritsFluentSetter.class, String.class),
				record.fields()[0].setter().orElseThrow().type());
		InheritsFluentSetter value = new InheritsFluentSetter();
		value.setValue("anchor");
		for (InheritsFluentSetter copy : roundTrips(value)) {
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
	void aSetterTakingAnotherElementTypeThanItsGetterReturnsPairsWithNoGetter() throws Throwable {
		DataClassRecord record = record(OtherElementSetter.class);
		assertEquals(List.of("name:String set"), shape(record));
		OtherElementSetter value = new OtherElementSetter();
		value.name = "anchor";
		value.setItems(List.of(1, 2));
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (OtherElementSetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals(List.of(), copy.getItems());
		}
	}

	@Test
	void aSetterDifferingFromItsGetterOnlyInANestedArgumentPairsWithNoGetter() throws Throwable {
		DataClassRecord record = record(OtherNestedArgumentSetter.class);
		assertEquals(List.of("name:String set"), shape(record));
		OtherNestedArgumentSetter value = new OtherNestedArgumentSetter();
		value.name = "anchor";
		value.setRows(List.of(List.of("kept back")));
		for (OtherNestedArgumentSetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals(List.of(), copy.getRows());
		}
	}

	@Test
	void aRawSetterPairsWithNoParameterizedGetter() throws Throwable {
		DataClassRecord record = record(RawSetter.class);
		assertEquals(List.of("name:String set"), shape(record));
		RawSetter value = new RawSetter();
		value.name = "anchor";
		value.setItems(List.of("kept back"));
		for (RawSetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals(List.of(), copy.getItems());
		}
	}

	@Test
	void aPairWhoseNestedArgumentsAgreeIsAComponent() throws Throwable {
		DataClassRecord record = record(NestedArgumentPair.class);
		assertEquals(List.of("rows:List set"), shape(record));
		NestedArgumentPair value = new NestedArgumentPair();
		value.setRows(List.of(List.of(1, 2), List.of(3)));
		for (NestedArgumentPair copy : roundTrips(value)) {
			assertEquals(List.of(List.of(1, 2), List.of(3)), copy.getRows());
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
		assertEquals(HiddenWithPublicField.class.getName() + " is bound through a member that is not accessible: field "
				+ HiddenWithPublicField.class.getName() + ".name, through no class the public lookup can access",
				refused.getMessage());
		assertInstanceOf(IllegalAccessException.class, refused.getCause());
	}

	@Test
	void aPublicFieldASuperClassThatIsNotPublicDeclaresIsReachedThroughTheDescribedClass() throws Throwable {
		DataClassRecord record = record(InheritsHiddenField.class);
		assertEquals(List.of("name:String set"), shape(record));
		InheritsHiddenField written = new InheritsHiddenField();
		record.fields()[0].set(written, "buoy");
		assertEquals("buoy", written.name);
		InheritsHiddenField value = new InheritsHiddenField();
		value.name = "anchor";
		for (InheritsHiddenField copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
		}
	}

	@Test
	void aPublicFieldAnotherHidesIsReadAndWrittenApartFromIt() throws Throwable {
		DataClassRecord record = record(HidesNamedField.class);
		assertEquals(List.of("name:String set", "own:String set"), shape(record));
		HidesNamedField value = new HidesNamedField();
		value.name = "own";
		((NamedFieldBase) value).name = "inherited";
		assertEquals(Map.of("name", "inherited", "own", "own"), new MapMapper(context).toMap(value));
		for (HidesNamedField copy : roundTrips(value)) {
			assertEquals("own", copy.name);
			assertEquals("inherited", ((NamedFieldBase) copy).name);
		}
	}

	@Test
	void aPairASuperClassThatIsNotPublicDeclaresIsReachedThroughTheDescribedClass() throws Throwable {
		DataClassRecord record = record(InheritsHiddenPair.class);
		assertEquals(List.of("value:String set"), shape(record));
		InheritsHiddenPair value = new InheritsHiddenPair();
		value.setValue("anchor");
		for (InheritsHiddenPair copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aGenericPairASuperClassThatIsNotPublicDeclaresIsAtTheTypeTheSubclassBinds() throws Throwable {
		DataClassRecord record = record(InheritsHiddenGenericPair.class);
		assertEquals(List.of("value:String set"), shape(record));
		InheritsHiddenGenericPair value = new InheritsHiddenGenericPair();
		value.setValue("anchor");
		for (InheritsHiddenGenericPair copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	@Test
	void aSetterPairsWithTheGetterASuperClassThatIsNotPublicDeclares() throws Throwable {
		DataClassRecord record = record(WritesHiddenGetter.class);
		assertEquals(List.of("value:String set"), shape(record));
		WritesHiddenGetter value = new WritesHiddenGetter();
		value.setValue("anchor");
		for (WritesHiddenGetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.getValue());
		}
	}

	static class HiddenWithPublicPair {

		private String value;

		@Record
		public HiddenWithPublicPair() {
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}
	}

	@Test
	void aPairOfAClassThePublicLookupCannotReachIsRefusedNamingThePairWithTheCauseKept() {
		DataBindException refused = refusal(HiddenWithPublicPair.class);
		assertEquals(HiddenWithPublicPair.class.getName() + " is bound through a member that is not accessible: method "
				+ HiddenWithPublicPair.class.getName() + ".getValue, through no class the public lookup can access",
				refused.getMessage());
		assertInstanceOf(IllegalAccessException.class, refused.getCause());
	}

	// --- generic public fields of a super class

	@Test
	void aGenericPublicFieldASuperClassDeclaresIsAComponentAtTheTypeTheSubclassBinds() throws Throwable {
		DataClassRecord record = record(TextFields.class);
		assertEquals(List.of("items:List set", "value:String set"), shape(record));
		assertEquals(String.class, assertInstanceOf(DataClassArray.class, record.fields()[0].dataClass())
				.arrayDataClass().typeClass());
		assertEquals(MethodType.methodType(String.class, TextFields.class), record.fields()[1].accessor().type());
		assertEquals(MethodType.methodType(void.class, TextFields.class, String.class),
				record.fields()[1].setter().orElseThrow().type());
		TextFields value = new TextFields();
		value.value = "anchor";
		value.items = List.of("anchor", "chain");
		for (TextFields copy : roundTrips(value)) {
			assertEquals("anchor", copy.value);
			assertEquals(List.of("anchor", "chain"), copy.items);
		}
	}

	@Test
	void aPublicFieldOfATypeVariableASuperClassDeclaresIsAtTheClassTheSubclassBinds() throws Throwable {
		DataClassRecord record = record(TextValue.class);
		assertEquals(List.of("value:String set"), shape(record));
		TextValue value = new TextValue();
		value.value = "anchor";
		assertEquals(Map.of("value", "anchor"), new MapMapper(context).toMap(value));
		for (TextValue copy : roundTrips(value)) {
			assertEquals("anchor", copy.value);
		}
	}

	@Test
	void aGenericPublicFieldIsBoundThroughAGenericClassBetween() throws Throwable {
		DataClassRecord record = record(TextListFields.class);
		assertEquals(List.of("items:List set", "value:List set"), shape(record));
		DataClassArray rows = assertInstanceOf(DataClassArray.class, record.fields()[0].dataClass());
		assertEquals(String.class, assertInstanceOf(DataClassArray.class, rows.arrayDataClass())
				.arrayDataClass().typeClass());
		assertEquals(String.class, assertInstanceOf(DataClassArray.class, record.fields()[1].dataClass())
				.arrayDataClass().typeClass());
		TextListFields value = new TextListFields();
		value.value = List.of("anchor");
		value.items = List.of(List.of("anchor", "chain"), List.of());
		for (TextListFields copy : roundTrips(value)) {
			assertEquals(List.of("anchor"), copy.value);
			assertEquals(List.of(List.of("anchor", "chain"), List.of()), copy.items);
		}
	}

	/** Read and written through the component's own handles: neither mapper carries a map component. */
	@Test
	void aGenericMapPublicFieldASuperClassDeclaresKeepsTheValueTypeTheSubclassBinds() throws Throwable {
		DataClassRecord record = record(CountFields.class);
		assertEquals(List.of("counts:Map set"), shape(record));
		DataClassMap counts = assertInstanceOf(DataClassMap.class, record.fields()[0].dataClass());
		assertEquals(String.class, counts.keyDataClass().typeClass());
		assertEquals(Integer.class, counts.valueDataClass().typeClass());
		CountFields value = new CountFields();
		record.fields()[0].set(value, Map.of("anchor", 1));
		assertEquals(Map.of("anchor", 1), value.counts);
		assertEquals(Map.of("anchor", 1), record.fields()[0].get(value));
	}

	@Test
	void aGenericPublicFieldLeftOpenIsBoundWhereTheClassIsDescribedAtAnArgument() throws Throwable {
		DataClassRecord record = record(HoldsOpenCounts.class);
		DataClassRecord held = assertInstanceOf(DataClassRecord.class, record.fields()[0].dataClass());
		assertEquals(Integer.class,
				assertInstanceOf(DataClassMap.class, held.fields()[0].dataClass()).valueDataClass().typeClass());
	}

	/** As a pair of a super class extended raw is: the variable is unbound, and erases to {@code Object}. */
	@Test
	void aGenericPublicFieldOfASuperClassExtendedRawIsRefusedAsItsPairIs() {
		assertEquals(message(RawBox.class), message(RawValue.class));
		assertEquals("Unrecognized parameterized type", message(RawFields.class));
	}

	@Test
	void aGenericPublicFieldOfAClassDescribedRawIsRefused() {
		assertEquals("Unrecognized parameterized type for map key/value", message(OpenCounts.class));
	}

	// --- a type variable inside an array or a wildcard

	@Test
	void aGenericArrayPairASuperClassDeclaresIsAtTheArrayClassTheSubclassBinds() throws Throwable {
		DataClassRecord record = record(TextArray.class);
		assertEquals(List.of("items:String[] set"), shape(record));
		assertEquals(String.class, assertInstanceOf(DataClassArray.class, record.fields()[0].dataClass())
				.arrayDataClass().typeClass());
		TextArray value = new TextArray();
		value.setItems(new String[] { "anchor", "chain" });
		for (TextArray copy : roundTrips(value)) {
			assertArrayEquals(new String[] { "anchor", "chain" }, copy.getItems());
			assertEquals(String[].class, copy.getItems().getClass());
		}
	}

	@Test
	void aGenericArrayPublicFieldASuperClassDeclaresIsAtTheArrayClassTheSubclassBinds() throws Throwable {
		DataClassRecord record = record(TextArrayFields.class);
		assertEquals(List.of("items:String[] set"), shape(record));
		TextArrayFields value = new TextArrayFields();
		value.items = new String[] { "anchor", "chain" };
		for (TextArrayFields copy : roundTrips(value)) {
			assertArrayEquals(new String[] { "anchor", "chain" }, copy.items);
			assertEquals(String[].class, copy.items.getClass());
		}
	}

	@Test
	void aSuperClassArrayPairWhoseGetterAloneIsOverriddenIsOneComponentAtTheOverridingType() throws Throwable {
		DataClassRecord record = record(TextArrayGetter.class);
		assertEquals(List.of("items:String[] set", "name:String set"), shape(record));
		TextArrayGetter value = new TextArrayGetter();
		value.name = "anchor";
		value.setItems(new String[] { "chain" });
		for (TextArrayGetter copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertArrayEquals(new String[] { "chain" }, copy.getItems());
		}
	}

	/** As a record's array of a parameterized type is: the binder carries no generic array type. */
	@Test
	void aSuperClassPairOfAGenericArrayWhoseGetterAloneIsOverriddenIsRefusedAsAGenericArrayComponentIs() {
		assertEquals("Collection must provide parameterized type information", message(ListArrayRecord.class));
		assertEquals(message(ListArrayRecord.class), message(TextListArrayGetter.class));
	}

	/** As a record's wildcard component is: the binder describes no wildcard type argument. */
	@Test
	void aWildcardPairOrPublicFieldASuperClassDeclaresIsRefusedAsAWildcardComponentIs() {
		assertEquals("Unrecognized parameterized type", message(WildcardRecord.class));
		assertEquals(message(WildcardRecord.class), message(TextWildcard.class));
		assertEquals(message(WildcardRecord.class), message(TextWildcardFields.class));
	}

	@Test
	void aSuperClassWildcardPairWhoseGetterAloneIsOverriddenIsRefusedAsAWildcardComponentIs() {
		assertEquals(message(WildcardRecord.class), message(TextWildcardGetter.class));
	}

	public record ListArrayRecord(List<String>[] rows) {
	}

	public record WildcardRecord(List<? extends String> items) {
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
