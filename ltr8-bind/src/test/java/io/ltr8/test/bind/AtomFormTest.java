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
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.AtomForms.AccessorBesideToData;
import io.ltr8.test.data.AtomForms.AccessorOfAnotherType;
import io.ltr8.test.data.AtomForms.AccessorTakingAParameter;
import io.ltr8.test.data.AtomForms.BareToData;
import io.ltr8.test.data.AtomForms.BoxedConstructorAndPrimitiveAccessor;
import io.ltr8.test.data.AtomForms.ConstructorAndAccessor;
import io.ltr8.test.data.AtomForms.ConstructorAndToData;
import io.ltr8.test.data.AtomForms.ConstructorNotPublic;
import io.ltr8.test.data.AtomForms.FactoryAndAccessor;
import io.ltr8.test.data.AtomForms.FactoryAndToData;
import io.ltr8.test.data.AtomForms.FactoryBesideConstructor;
import io.ltr8.test.data.AtomForms.FactoryReturningAnotherType;
import io.ltr8.test.data.AtomForms.FactoryWithoutWayOut;
import io.ltr8.test.data.AtomForms.Holder;
import io.ltr8.test.data.AtomForms.InheritsMarkedAccessor;
import io.ltr8.test.data.AtomForms.InheritsToData;
import io.ltr8.test.data.AtomForms.InheritsToDataThroughATypeArgument;
import io.ltr8.test.data.AtomForms.Keys;
import io.ltr8.test.data.AtomForms.Marked;
import io.ltr8.test.data.AtomForms.MarkedToData;
import io.ltr8.test.data.AtomForms.OpenToDataAtom;
import io.ltr8.test.data.AtomForms.OutOfRange;
import io.ltr8.test.data.AtomForms.Plain;
import io.ltr8.test.data.AtomForms.RawToDataAtom;
import io.ltr8.test.data.AtomForms.RecordWithAtomConstructor;
import io.ltr8.test.data.AtomForms.ToDataOfARecord;
import io.ltr8.test.data.AtomForms.ToDataOfAnotherType;
import io.ltr8.test.data.AtomForms.ToDataTypeOnly;
import io.ltr8.test.data.AtomForms.ToDataWithoutAtom;
import io.ltr8.test.data.AtomForms.TwoAccessors;
import io.ltr8.test.data.AtomForms.TwoFactories;
import io.ltr8.test.data.AtomForms.TypeOnly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An atom says both its way in and its way out, and nothing is found by name: {@code @Atom} on one public constructor
 * or public static method taking a value the wire carries, and {@code @Atom} on one public instance method returning
 * it or {@code ToData<S>} with {@code S} that value. Anything else is refused, naming the class.
 */
class AtomFormTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private final MapMapper maps = new MapMapper(context);

	private final ArrayMapper arrays = new ArrayMapper(context);

	/** One atom, the value it is written as, and the class that value is described by. */
	private record Form(Object atom, Object data, Class<?> dataClass) {
	}

	private static final List<Form> FORMS = List.of(
			new Form(new ConstructorAndAccessor("A-1"), "A-1", String.class),
			new Form(new ConstructorAndToData("A-2"), "A-2", String.class),
			new Form(FactoryAndAccessor.of(3), 3, int.class),
			new Form(FactoryAndToData.of(4), 4, int.class),
			new Form(new BoxedConstructorAndPrimitiveAccessor(5), 5, Integer.class),
			new Form(new MarkedToData("A-6"), "A-6", String.class),
			new Form(new InheritsToData("A-7"), "A-7", String.class),
			new Form(new InheritsToDataThroughATypeArgument(8L), 8L, long.class),
			new Form(new InheritsMarkedAccessor("A-9"), "A-9", String.class),
			new Form(Plain.ONE, "ONE", String.class),
			new Form(Marked.TWO, "TWO", String.class));

	// --- bound

	@Test
	void eachFormIsAnAtomDescribedByTheValueItsWayInTakes() {
		assertAll(FORMS.stream().<Executable>map(form -> () -> {
			DataClassAtom atom = assertInstanceOf(DataClassAtom.class, context.getDescriptor(form.atom().getClass()));
			assertEquals(form.dataClass(), atom.dataClass(), form.atom().getClass().getSimpleName());
			assertTrue(atom.bridge().isPresent(), form.atom().getClass().getSimpleName());
		}));
	}

	@Test
	void eachFormRoundTripsAsARootValue() {
		assertAll(FORMS.stream().<Executable>map(form -> () -> {
			DataClass descriptor = context.getDescriptor(form.atom().getClass());
			Object written = maps.toMap(descriptor, form.atom());
			assertEquals(form.data(), written);
			Object read = maps.toObject(descriptor, written);
			assertEquals(form.atom().getClass(), read.getClass());
			assertEquals(written, maps.toMap(descriptor, read));
		}));
	}

	@Test
	void eachFormRoundTripsAsARecordComponentThroughBothMappers() {
		assertAll(FORMS.stream().flatMap(form -> {
			DataClass holder = described(Holder.class, form.atom().getClass());
			Holder<Object> value = new Holder<>(form.atom());
			String name = form.atom().getClass().getSimpleName();
			return Stream.<Executable>of(() -> {
				Object written = maps.toMap(holder, value);
				assertEquals(Map.of("value", form.data()), written, name);
				Holder<?> read = assertInstanceOf(Holder.class, maps.toObject(holder, written));
				assertEquals(form.atom().getClass(), read.value().getClass(), name);
				assertEquals(written, maps.toMap(holder, read), name);
			}, () -> {
				Object[] written = arrays.toArray(holder, value);
				assertEquals(List.of(form.data()), Arrays.asList(written), name);
				Holder<?> read = assertInstanceOf(Holder.class, arrays.toObject(holder, written));
				assertEquals(form.atom().getClass(), read.value().getClass(), name);
				assertEquals(Arrays.asList(written), Arrays.asList(arrays.toArray(holder, read)), name);
			});
		}));
	}

	@Test
	void eachFormRoundTripsAsAMapKeyThroughBothMappers() {
		assertAll(FORMS.stream().flatMap(form -> {
			DataClass keys = described(Keys.class, form.atom().getClass());
			Keys<Object> value = new Keys<>(Map.of(form.atom(), "x"));
			String name = form.atom().getClass().getSimpleName();
			return Stream.<Executable>of(() -> {
				Object written = maps.toMap(keys, value);
				assertEquals(Map.of("keys", Map.of(form.data(), "x")), written, name);
				Keys<?> read = assertInstanceOf(Keys.class, maps.toObject(keys, written));
				assertEquals(form.atom().getClass(), read.keys().keySet().iterator().next().getClass(), name);
				assertEquals(written, maps.toMap(keys, read), name);
			}, () -> {
				Object[] written = arrays.toArray(keys, value);
				Object[] entry = (Object[]) ((Object[]) written[0])[0];
				assertEquals(List.of(form.data(), "x"), Arrays.asList(entry), name);
				Keys<?> read = assertInstanceOf(Keys.class, arrays.toObject(keys, written));
				assertEquals(form.atom().getClass(), read.keys().keySet().iterator().next().getClass(), name);
			});
		}));
	}

	/** Registering a class as an atom keeps the form it says, as describing it would. */
	@Test
	void eachFormKeepsItsFormWhenRegisteredAsAnAtom() {
		assertAll(FORMS.stream().<Executable>map(form -> () -> {
			Class<?> type = form.atom().getClass();
			DataClass registered = DataBindContext.builder().registerAtom(type).build().getDescriptor(type);
			assertEquals(form.dataClass(), registered.dataClass(), type.getSimpleName());
			assertTrue(registered.bridge().isPresent(), type.getSimpleName());
		}));
	}

	@Test
	void aRecordMarkingAnAtomConstructorStaysARecord() throws DataBindException {
		assertInstanceOf(DataClassRecord.class, context.getDescriptor(RecordWithAtomConstructor.class));
	}

	@Test
	void aToDataClassMarkingNoWayInStaysABridgedRecord() {
		assertEquals("Could not find constructor: class java.lang.String", refusal(ToDataWithoutAtom.class));
	}

	// --- refused: no way out said

	@Test
	void aBareToDataIsNoWayOut() {
		String atom = BareToData.class.getName();
		assertEquals(atom + " marks constructor " + atom + "(java.lang.String) @Atom, its way in, and says no way "
				+ "out: mark the public instance method returning java.lang.String @Atom, or implement "
				+ "ToData<java.lang.String>", refusal(BareToData.class));
	}

	@Test
	void aFactoryWithNoWayOutIsToldToSayOneNamingTheBox() {
		String atom = FactoryWithoutWayOut.class.getName();
		assertEquals(atom + " marks method " + atom + ".of(int) @Atom, its way in, and says no way out: mark the "
				+ "public instance method returning int @Atom, or implement ToData<java.lang.Integer>",
				refusal(FactoryWithoutWayOut.class));
	}

	// --- refused: no way in said

	@Test
	void atomOnTheTypeAloneIsRefused() {
		assertAll(Stream.of(TypeOnly.class, ToDataTypeOnly.class).<Executable>map(type -> () -> assertEquals(type.getName()
				+ " carries @Atom and marks no constructor or static method @Atom: an atom's way in is one public "
				+ "constructor or public static method marked @Atom, taking a value the wire carries",
				refusal(type))));
	}

	// --- refused: two ways in, or two ways out

	@Test
	void twoFactoriesAreRefused() {
		String atom = TwoFactories.class.getName();
		assertEquals(atom + " marks two ways in @Atom, method " + atom + ".first(int) and method " + atom
				+ ".second(int): an atom has one", refusal(TwoFactories.class));
	}

	@Test
	void aFactoryBesideAConstructorIsRefused() {
		String atom = FactoryBesideConstructor.class.getName();
		assertEquals(atom + " marks two ways in @Atom, constructor " + atom + "(int) and method " + atom
				+ ".of(int): an atom has one", refusal(FactoryBesideConstructor.class));
	}

	@Test
	void twoMarkedAccessorsAreRefused() {
		String atom = TwoAccessors.class.getName();
		assertEquals(atom + " says two ways out, method " + atom + ".code() marked @Atom and method " + atom
				+ ".label() marked @Atom: an atom has one", refusal(TwoAccessors.class));
	}

	@Test
	void aMarkedAccessorBesideToDataIsRefused() {
		String atom = AccessorBesideToData.class.getName();
		assertEquals(atom + " says two ways out, method " + atom + ".code() marked @Atom and method " + atom
				+ ".toData() of ToData<java.lang.String>: an atom has one", refusal(AccessorBesideToData.class));
	}

	// --- refused: the two halves disagree

	@Test
	void anAccessorOfAnotherTypeIsRefused() {
		String atom = AccessorOfAnotherType.class.getName();
		assertEquals(atom + "'s way in constructor " + atom + "(java.lang.String) takes java.lang.String and its way "
				+ "out method " + atom + ".length() marked @Atom returns int: both halves of an atom carry one value",
				refusal(AccessorOfAnotherType.class));
	}

	@Test
	void aToDataOfAnotherTypeIsRefused() {
		String atom = ToDataOfAnotherType.class.getName();
		assertEquals(atom + "'s way in method " + atom + ".of(int) takes int and its way out method " + atom
				+ ".toData() of ToData<java.lang.String> returns java.lang.String: both halves of an atom carry one "
				+ "value", refusal(ToDataOfAnotherType.class));
	}

	@Test
	void aToDataOfARecordBesideAWayInIsRefused() {
		String atom = ToDataOfARecord.class.getName();
		String record = OutOfRange.class.getName();
		assertEquals(atom + "'s way in constructor " + atom + "(java.lang.String) takes java.lang.String and its way "
				+ "out method " + atom + ".toData() of ToData<" + record + "> returns " + record + ": both halves of "
				+ "an atom carry one value", refusal(ToDataOfARecord.class));
	}

	// --- refused: @Atom where it cannot apply

	@Test
	void aFactoryReturningAnotherTypeIsRefused() {
		String atom = FactoryReturningAnotherType.class.getName();
		assertEquals(atom + " marks method " + atom + ".of(int) @Atom, which returns java.lang.String: a static way "
				+ "in returns " + atom, refusal(FactoryReturningAnotherType.class));
	}

	@Test
	void anAccessorTakingAParameterIsRefused() {
		String atom = AccessorTakingAParameter.class.getName();
		assertEquals(atom + " marks method " + atom + ".code(int) @Atom, which takes parameters: an instance method "
				+ "marked @Atom is the way out, and takes none", refusal(AccessorTakingAParameter.class));
	}

	@Test
	void aConstructorThatIsNotPublicIsRefused() {
		String atom = ConstructorNotPublic.class.getName();
		assertEquals(atom + " is bound through a member that is not accessible: constructor " + atom
				+ "(java.lang.String), which is not public", refusal(ConstructorNotPublic.class));
	}

	@Test
	void aRawToDataBesideAWayInIsRefused() {
		String atom = RawToDataAtom.class.getName();
		assertEquals(atom + " implements ToData raw: a way out through ToData names the value it returns, "
				+ "ToData<S>", refusal(RawToDataAtom.class));
	}

	@Test
	void anOpenToDataBesideAWayInIsRefused() {
		String atom = OpenToDataAtom.class.getName();
		assertEquals(atom + " implements ToData<T>: a way out through ToData names the value it returns by a class",
				refusal(OpenToDataAtom.class));
	}

	/** A refusal reaches a caller registering the class as an atom unchanged. */
	@Test
	void aRefusalPassesUpThroughRegistrationAsItself() {
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> DataBindContext.builder().registerAtom(BareToData.class).build());
		assertEquals(refusal(BareToData.class), thrown.getCause().getMessage());
	}

	private DataClass described(Class<?> holder, Type argument) {
		try {
			return context.getDescriptor(holder, new DataParameterizedType(holder, argument));
		} catch (DataBindException e) {
			throw new AssertionError(e);
		}
	}

	private static String refusal(Class<?> type) {
		DataBindException thrown = assertThrows(DataBindException.class,
				() -> DataBindContext.builder().build().getDescriptor(type));
		return thrown.getMessage();
	}
}
