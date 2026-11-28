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
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.test.data.Typenames.BlankSlot;
import io.ltr8.test.data.Typenames.BlankToken;
import io.ltr8.test.data.Typenames.DerivedCode;
import io.ltr8.test.data.Typenames.EmptyName;
import io.ltr8.test.data.Typenames.HoldsBlankSlot;
import io.ltr8.test.data.Typenames.HoldsContainers;
import io.ltr8.test.data.Typenames.HoldsEmbeddedUnion;
import io.ltr8.test.data.Typenames.NamedCode;
import io.ltr8.test.data.Typenames.NamedColour;
import io.ltr8.test.data.Typenames.NamedPair;
import io.ltr8.test.data.Typenames.NamedRecord;
import io.ltr8.test.data.Typenames.NamedShape;
import io.ltr8.test.data.Typenames.NamedToken;
import io.ltr8.test.data.Typenames.NamedWrapper;
import io.ltr8.test.data.Typenames.PlainCode;
import io.ltr8.test.data.Typenames.PlainColour;
import io.ltr8.test.data.Typenames.PlainPair;
import io.ltr8.test.data.Typenames.PlainRecord;
import io.ltr8.test.data.Typenames.PlainShape;
import io.ltr8.test.data.Typenames.PlainToken;
import io.ltr8.test.data.Typenames.PlainWrapper;
import io.ltr8.test.data.Typenames.Square;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A descriptor of every kind exposes the {@code @Typename} its class carries itself, and nothing in its place. */
class TypenameTest {

	private static final DataBindContext CONTEXT = DataBindContext.builder()
			.registerAtom(NamedToken.class)
			.registerAtom(PlainToken.class)
			.build();

	@Test
	void aRecord() throws DataBindException {
		assertNamed(DataClassRecord.class, NamedRecord.class, "named_record");
		assertUnnamed(DataClassRecord.class, PlainRecord.class);
	}

	@Test
	void aTuple() throws DataBindException {
		assertNamed(DataClassTuple.class, NamedPair.class, "named_pair");
		assertUnnamed(DataClassTuple.class, PlainPair.class);
	}

	@Test
	void anAtom() throws DataBindException {
		assertNamed(DataClassAtom.class, NamedCode.class, "named_code");
		assertUnnamed(DataClassAtom.class, PlainCode.class);
		assertUnnamed(DataClassAtom.class, String.class);
	}

	@Test
	void aRegisteredAtom() throws DataBindException {
		assertNamed(DataClassAtom.class, NamedToken.class, "named_token");
		assertUnnamed(DataClassAtom.class, PlainToken.class);
	}

	@Test
	void anEnum() throws DataBindException {
		assertNamed(DataClassAtom.class, NamedColour.class, "named_colour");
		assertUnnamed(DataClassAtom.class, PlainColour.class);
	}

	@Test
	void aUnionAndItsMember() throws DataBindException {
		assertNamed(DataClassUnion.class, NamedShape.class, "named_shape");
		assertNamed(DataClassRecord.class, Square.class, "square");
		assertUnnamed(DataClassUnion.class, PlainShape.class);
	}

	@Test
	void anEmbeddedUnion() throws DataBindException {
		DataClassRecord holder = (DataClassRecord) CONTEXT.getDescriptor(HoldsEmbeddedUnion.class);
		assertInstanceOf(DataClassUnion.class, holder.fields()[0].dataClass());
		assertEquals(Optional.empty(), holder.fields()[0].dataClass().typeName());
	}

	@Test
	void anArrayAndAMap() throws DataBindException {
		DataClassRecord holder = (DataClassRecord) CONTEXT.getDescriptor(HoldsContainers.class);
		assertInstanceOf(DataClassArray.class, holder.fields()[0].dataClass());
		assertEquals(Optional.of("named_bag"), holder.fields()[0].dataClass().typeName());
		assertEquals(Optional.empty(), holder.fields()[1].dataClass().typeName());
		assertInstanceOf(DataClassMap.class, holder.fields()[2].dataClass());
		assertEquals(Optional.of("named_table"), holder.fields()[2].dataClass().typeName());
		assertEquals(Optional.empty(), holder.fields()[3].dataClass().typeName());
	}

	/** A transparent wrapper's descriptor is of its wrapped component's kind, and named for the wrapper. */
	@Test
	void aTransparentWrapper() throws DataBindException {
		assertNamed(DataClassAtom.class, NamedWrapper.class, "named_wrapper");
		assertUnnamed(DataClassAtom.class, PlainWrapper.class);
	}

	@Test
	void aSuperClassesNameIsNotInherited() throws DataBindException {
		assertUnnamed(DataClassAtom.class, DerivedCode.class);
	}

	@Test
	void aBlankNameIsRefused() {
		assertEquals(EmptyName.class.getName() + " carries @Typename(name = \"\"), which is blank",
				assertThrows(DataBindException.class, () -> CONTEXT.getDescriptor(EmptyName.class)).getMessage());
		assertEquals(BlankSlot.class.getName() + " carries @Typename(name = \" \"), which is blank",
				assertThrows(DataBindException.class, () -> CONTEXT.getDescriptor(HoldsBlankSlot.class)).getMessage());
	}

	@Test
	void aBlankNameOnARegisteredAtomIsRefused() {
		DataBindContext.Builder builder = DataBindContext.builder().registerAtom(BlankToken.class);
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, builder::build);
		assertEquals(BlankToken.class.getName() + " carries @Typename(name = \" \t\"), which is blank",
				refused.getCause().getMessage());
	}

	private static void assertNamed(Class<? extends DataClass> kind, Class<?> type, String name)
			throws DataBindException {
		DataClass descriptor = CONTEXT.getDescriptor(type);
		assertInstanceOf(kind, descriptor, type.getSimpleName());
		assertEquals(Optional.of(name), descriptor.typeName(), type.getSimpleName());
	}

	private static void assertUnnamed(Class<? extends DataClass> kind, Class<?> type) throws DataBindException {
		DataClass descriptor = CONTEXT.getDescriptor(type);
		assertInstanceOf(kind, descriptor, type.getSimpleName());
		assertEquals(Optional.empty(), descriptor.typeName(), type.getSimpleName());
	}
}
