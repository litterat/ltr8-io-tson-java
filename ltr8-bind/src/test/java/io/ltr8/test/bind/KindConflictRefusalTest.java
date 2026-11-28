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
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.test.data.KindConflicts.AbstractRecord;
import io.ltr8.test.data.KindConflicts.AbstractRecordConstructor;
import io.ltr8.test.data.KindConflicts.AbstractRecordUnion;
import io.ltr8.test.data.KindConflicts.AtomEnumToData;
import io.ltr8.test.data.KindConflicts.AtomUnion;
import io.ltr8.test.data.KindConflicts.ConcreteUnion;
import io.ltr8.test.data.KindConflicts.EnumToData;
import io.ltr8.test.data.KindConflicts.EnumUnion;
import io.ltr8.test.data.KindConflicts.RecordUnion;
import io.ltr8.test.data.KindConflicts.ToDataUnion;
import io.ltr8.test.data.KindConflicts.OrderedAtom;
import io.ltr8.test.data.KindConflicts.OrderedEnum;
import io.ltr8.test.data.KindConflicts.OrderedList;
import io.ltr8.test.data.KindConflicts.OrderedMap;
import io.ltr8.test.data.KindConflicts.OrderedRecord;
import io.ltr8.test.data.KindConflicts.OrderedUnion;
import io.ltr8.test.data.KindConflicts.RecordConstructorToData;
import io.ltr8.test.data.KindConflicts.RecordEnum;
import io.ltr8.test.data.KindConflicts.RecordInterface;
import io.ltr8.test.data.KindConflicts.RecordOnHiddenConstructor;
import io.ltr8.test.data.KindConflicts.RecordOnHiddenRecordConstructor;
import io.ltr8.test.data.KindConflicts.RecordToData;
import io.ltr8.test.data.KindConflicts.SealedAbstractRecord;
import io.ltr8.test.data.KindConflicts.SealedListed;
import io.ltr8.test.data.KindConflicts.SealedMap;
import io.ltr8.test.data.KindConflicts.UnionCollection;
import io.ltr8.test.data.KindConflicts.UnionList;
import io.ltr8.test.data.KindConflicts.SealedListingUnion;
import io.ltr8.test.data.KindConflicts.SealedOpenUnion;
import io.ltr8.test.data.KindConflicts.TransparentRecord;
import io.ltr8.test.data.KindConflicts.TransparentRecordConstructor;
import io.ltr8.test.data.KindConflicts.TransparentToData;
import io.ltr8.test.data.KindConflicts.TransparentTuple;
import io.ltr8.test.data.KindConflicts.TupleRecord;
import io.ltr8.test.data.KindConflicts.TupleRecordAsText;
import io.ltr8.test.data.KindConflicts.TupleToData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A class whose marks say two kinds, or carry a mark its kind never reads, is refused with the fix named. */
class KindConflictRefusalTest {

	@Test
	void twoWaysToWriteARecordAreRefused() {
		assertAll(
				described(TransparentTuple.class, "says two ways to write it, @Transparent, as its one component, and "
						+ "@Tuple, as its components by position: remove one of them"),
				described(TransparentToData.class, "says two ways to write it, @Transparent, as its one component, "
						+ "and ToData, as the record class it names: remove one of them"),
				described(TransparentRecord.class, "says two ways to write it, @Transparent, as its one component, "
						+ "and @Record, as its components by name: remove one of them"),
				described(TransparentRecordConstructor.class, "says two ways to write it, @Transparent, as its one "
						+ "component, and @Record on the constructor TransparentRecordConstructor(String), as its "
						+ "components by name: remove one of them"),
				described(TupleToData.class, "says two ways to write it, @Tuple, as its components by position, and "
						+ "ToData, as the record class it names: remove one of them"),
				described(TupleRecord.class, "says two ways to write it, @Tuple, as its components by position, and "
						+ "@Record, as its components by name: remove one of them"));
	}

	@Test
	void aRecordNothingCanBuildIsRefused() {
		String built = "which describes a class built through its constructor: ";
		assertAll(
				described(AbstractRecord.class, "is an abstract class, which nothing can build, but carries @Record, "
						+ built + "make AbstractRecord concrete, or remove it"),
				described(AbstractRecordConstructor.class, "is an abstract class, which nothing can build, but carries "
						+ "@Record on the constructor AbstractRecordConstructor(String), " + built
						+ "make AbstractRecordConstructor concrete, or remove it"),
				described(AbstractRecordUnion.class, "is an abstract class carrying @Union, a union of its members, but "
						+ "carries @Record, " + built + "remove it"),
				described(SealedAbstractRecord.class, "is a sealed abstract class, a union of the classes it permits, "
						+ "but carries @Record, " + built + "remove it"),
				described(RecordInterface.class, "is an interface, which nothing can build, but carries @Record, "
						+ built + "remove it"));
	}

	@Test
	void aUnionMarkOnAClassThatIsNoUnionIsRefused() {
		String belongs = "; @Union belongs on an interface or abstract class, written as one of its members: remove it";
		assertAll(
				described(RecordUnion.class, "carries @Union, but is a record, written as its components" + belongs),
				described(EnumUnion.class, "carries @Union, but is an enum, written as its constant's name" + belongs),
				described(AtomUnion.class, "carries @Union, but is an atom, written as the value its way out returns"
						+ belongs),
				registered(AtomUnion.class, "carries @Union, but is registered as an atom, written as the value its way "
						+ "out returns" + belongs),
				registered(EnumUnion.class, "carries @Union, but is an enum, written as its constant's name"
						+ belongs),
				described(ConcreteUnion.class, "carries @Union, but is a concrete class" + belongs),
				described(ToDataUnion.class, "carries @Union, a union of its members, but implements ToData, bridged "
						+ "to the record class it names: remove one of them"));
	}

	/** Even a list naming exactly what {@code permits} names is never read. */
	@Test
	void aSealedUnionStatingItsMembersAgainIsRefused() {
		String permits = ", so its members are the classes it permits, but carries ";
		String remove = ": the members come from permits, so remove its attributes";
		assertAll(
				described(SealedListingUnion.class, "is sealed" + permits + "@Union({" + SealedListed.class.getName()
						+ ".class})" + remove),
				described(SealedOpenUnion.class, "is sealed" + permits + "@Union(value = {}, sealed = false)"
						+ remove));
	}

	@Test
	void aUnionThatIsAContainerTooIsRefused() {
		String union = ", so it could mean a union, written as one of its members, or ";
		assertAll(
				described(SealedMap.class, "is sealed and a java.util.Map" + union + "a map, written as its entries: "
						+ "unseal it, or extend no java.util.Map"),
				described(UnionCollection.class, "carries @Union and is a java.util.Collection" + union + "a "
						+ "collection, written as its elements: remove @Union, or extend no java.util.Collection"),
				described(UnionList.class, "carries @Union and is a java.util.Collection" + union + "a collection, "
						+ "written as its elements: remove @Union, or extend no java.util.Collection"));
	}

	@Test
	void aRecordMarkOnAClassItsToDataDescribesIsRefused() {
		assertAll(
				described(RecordToData.class, "says two ways to write it, ToData, as the record class it names, and "
						+ "@Record, as its components by name: remove one of them"),
				described(RecordConstructorToData.class, "says two ways to write it, ToData, as the record class it "
						+ "names, and @Record on the constructor RecordConstructorToData(Text), as its components by "
						+ "name: remove one of them"));
	}

	@Test
	void aRecordMarkOnAConstructorThatIsNotPublicIsRefused() {
		assertAll(
				described(RecordOnHiddenConstructor.class, "marks the constructor RecordOnHiddenConstructor(String) "
						+ "@Record, but it is not public, so nothing builds RecordOnHiddenConstructor through it: make it "
						+ "public, or remove @Record"),
				described(RecordOnHiddenRecordConstructor.class, "marks the constructor "
						+ "RecordOnHiddenRecordConstructor(String) @Record, but it is not public, so nothing builds "
						+ "RecordOnHiddenRecordConstructor through it: make it public, or remove @Record"));
	}

	/** Both entry points give an enum its kind, and refuse alike what it does not read. */
	@Test
	void anEnumSayingAnotherKindIsRefused() {
		String toData = "is an enum, written as its constant's name, but implements ToData, which nothing reads: "
				+ "remove it; an enum written another way is registered with its bridge, registerAtom(";
		assertAll(
				described(EnumToData.class, toData + "EnumToData.class, bridge)"),
				registered(EnumToData.class, toData + "EnumToData.class, bridge)"),
				described(AtomEnumToData.class, toData + "AtomEnumToData.class, bridge)"),
				registered(AtomEnumToData.class, toData + "AtomEnumToData.class, bridge)"),
				described(RecordEnum.class, "is an enum, written as its constant's name, so nothing reads @Record: "
						+ "remove it"));
	}

	@Test
	void anOrderOnAKindWithNoComponentsOfItsOwnIsRefused() {
		String order = "; an order applies only to a class described by its own components, so remove it";
		assertAll(
				described(OrderedEnum.class, "carries @FieldOrder, but is an enum, written as its constant's name"
						+ order),
				registered(OrderedEnum.class, "carries @FieldOrder, but is an enum, written as its constant's name"
						+ order),
				described(OrderedUnion.class, "carries @FieldOrder, but is a union, written as one of its members"
						+ order),
				described(OrderedAtom.class, "carries @FieldOrder, but is an atom, written as the value its way out "
						+ "returns" + order),
				registered(OrderedAtom.class, "carries @FieldOrder, but is registered as an atom, written as the value "
						+ "its way out returns" + order),
				described(new DataParameterizedType(OrderedMap.class, String.class, String.class), OrderedMap.class,
						"carries @FieldOrder, but is a map, written as its entries" + order),
				described(new DataParameterizedType(OrderedList.class, String.class), OrderedList.class,
						"carries @FieldOrder, but is a collection, written as its elements" + order),
				registered(OrderedRecord.class, "carries @FieldOrder, but is registered as an atom with no form of its "
						+ "own" + order));
	}

	/** Registered with no bridge, a class is read, and a mark its atom form does not read refuses it. */
	@Test
	void aBareRegistrationRefusesAMarkItsAtomFormDoesNotRead() {
		String bare = "is registered as an atom with no form of its own, so nothing reads ";
		assertAll(
				registered(TupleRecord.class, bare + "@Tuple: remove it, or describe TupleRecord rather than register "
						+ "it as an atom"),
				registered(RecordToData.class, bare + "@Record: remove it, or describe RecordToData rather than "
						+ "register it as an atom"),
				registered(ToDataUnion.class, bare + "@Union: remove it, or describe ToDataUnion rather than register "
						+ "it as an atom"));
	}

	/** Registered with a bridge, the bridge is the atom's form, and the class's own marks are set aside. */
	@Test
	void aBridgedRegistrationSetsTheClassesMarksAside() throws DataBindException {
		DataClassAtom atom = assertInstanceOf(DataClassAtom.class, DataBindContext.builder()
				.registerAtom(TupleRecord.class, new TupleRecordAsText()).build().getDescriptor(TupleRecord.class));
		assertEquals(String.class, atom.dataClass());
	}

	/** Described in two fresh contexts, {@code type}'s name and {@code refusal} are its whole message chain. */
	private static Executable described(Class<?> type, String refusal) {
		return described(type, type, refusal);
	}

	private static Executable described(Type at, Class<?> type, String refusal) {
		return () -> assertAll(Stream.of(1, 2).<Executable>map(run -> () -> assertEquals(
				List.of(type.getName() + " " + refusal), messages(at, type), type.getSimpleName())));
	}

	/** Registered as an atom, {@code type}'s name and {@code refusal} are the message of the refusal it causes. */
	private static Executable registered(Class<?> type, String refusal) {
		return () -> {
			IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
					() -> DataBindContext.builder().registerAtom(type).build(), type.getSimpleName());
			assertEquals(type.getName() + " " + refusal, thrown.getCause().getMessage(), type.getSimpleName());
			assertNull(thrown.getCause().getCause(), type.getSimpleName());
		};
	}

	private static List<String> messages(Type at, Class<?> type) {
		List<String> messages = new ArrayList<>();
		try {
			DataBindContext.builder().build().getDescriptor(type, at);
			messages.add("described");
		} catch (Exception e) {
			for (Throwable cause = e; cause != null; cause = cause.getCause()) {
				messages.add(cause.getMessage());
			}
		}
		return messages;
	}
}
