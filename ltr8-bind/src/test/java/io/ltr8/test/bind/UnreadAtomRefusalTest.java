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
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.test.data.UnreadAtoms.AtomExtendingAtomBase;
import io.ltr8.test.data.UnreadAtoms.BridgedExtendingAtomBase;
import io.ltr8.test.data.UnreadAtoms.BridgedWithAccessor;
import io.ltr8.test.data.UnreadAtoms.CollectionWithAccessor;
import io.ltr8.test.data.UnreadAtoms.EnumBodyWithAccessor;
import io.ltr8.test.data.UnreadAtoms.EnumWithAccessor;
import io.ltr8.test.data.UnreadAtoms.EnumWithConstructor;
import io.ltr8.test.data.UnreadAtoms.EnumWithFactory;
import io.ltr8.test.data.UnreadAtoms.Label;
import io.ltr8.test.data.UnreadAtoms.MapWithAccessor;
import io.ltr8.test.data.UnreadAtoms.RecordExtendingAtomBase;
import io.ltr8.test.data.UnreadAtoms.SharedWayOutMember;
import io.ltr8.test.data.UnreadAtoms.UnionExtendingAtomBase;
import io.ltr8.test.data.UnreadAtoms.UnionMarkedOnType;
import io.ltr8.test.data.UnreadAtoms.UnionSharingAWayOut;
import io.ltr8.test.data.UnreadAtoms.UnionWithAccessor;
import io.ltr8.test.data.UnreadAtoms.UnionWithAtomConstructor;
import io.ltr8.test.data.UnreadAtoms.UnionWithFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@code @Atom} on a class described as something other than an atom, or on a super class's static method, is never
 * silently ignored: the class is refused, naming it, what it is described as and the member carrying {@code @Atom}.
 */
class UnreadAtomRefusalTest {

	private static final String UNION = "is a union, written as one of its members";

	private static final String ENUM = "is an enum, written as its constant's name";

	@Test
	void aUnionRefusesAWayInMarkedOnIt() {
		assertAll(
				refused(UnionWithFactory.class, UNION, "the method UnionWithFactory.of(String)"),
				refused(UnionMarkedOnType.class, UNION, "the interface UnionMarkedOnType"),
				refused(UnionWithAtomConstructor.class, UNION, "the constructor UnionWithAtomConstructor(String)"));
	}

	@Test
	void anEnumRefusesAtomOnItsMethodsAndConstructors() {
		assertAll(
				refused(EnumWithAccessor.class, ENUM, "the method EnumWithAccessor.code()"),
				refused(EnumWithFactory.class, ENUM, "the method EnumWithFactory.of(String)"),
				refused(EnumWithConstructor.class, ENUM, "the constructor EnumWithConstructor(String)"),
				refusedAs(EnumBodyWithAccessor.class, EnumBodyWithAccessor.class.getName() + " " + ENUM
						+ ", so nothing reads @Atom on the method code() of the body of its constant RED"));
	}

	@Test
	void aClassDescribedAsNoAtomRefusesAWayOutNoAtomExtendingItInherits() {
		assertAll(
				refused(UnionWithAccessor.class, UNION, "the method UnionWithAccessor.code()"),
				refused(new DataParameterizedType(MapWithAccessor.class, String.class, String.class),
						"is a map, written as its entries", "the method MapWithAccessor.text()"),
				refused(new DataParameterizedType(CollectionWithAccessor.class, String.class),
						"is a collection, written as its elements", "the method CollectionWithAccessor.text()"),
				refused(BridgedWithAccessor.class, "is bridged through ToData to " + Label.class.getName()
						+ ", which describes it", "the method BridgedWithAccessor.text()"));
	}

	/** The member reads the union's marked method as its way out. */
	@Test
	void aUnionKeepsAWayOutItsAtomMemberInherits() throws Exception {
		DataBindContext context = DataBindContext.builder().build();
		DataClassUnion union = assertInstanceOf(DataClassUnion.class, context.getDescriptor(UnionSharingAWayOut.class));
		assertArrayEquals(new Class<?>[] { SharedWayOutMember.class }, union.memberTypes());
		DataClassAtom member = assertInstanceOf(DataClassAtom.class, context.getDescriptor(SharedWayOutMember.class));
		assertEquals(String.class, member.dataClass());
	}

	@Test
	void aSuperClassesStaticWayInIsRefusedFromASubclass() {
		String method = "the method AtomBase.of(String)";
		assertAll(
				refused(AtomExtendingAtomBase.class, "is an atom bridged to java.lang.String", method),
				refused(RecordExtendingAtomBase.class, "is a record, written as its components", method),
				refused(UnionExtendingAtomBase.class, UNION, method),
				refused(BridgedExtendingAtomBase.class, "is bridged through ToData to " + Label.class.getName()
						+ ", which describes it", method));
	}

	private static Executable refused(Type type, String described, String member) {
		return refusedAs(type, type.getTypeName().replaceFirst("<.*", "") + " " + described
				+ ", so nothing reads @Atom on " + member);
	}

	/** In two fresh contexts, the refusal's message and its causes', outermost first. */
	private static Executable refusedAs(Type type, String... chain) {
		return () -> assertAll(Stream.of(1, 2).<Executable>map(run -> () -> assertEquals(List.of(chain),
				messages(type), type.getTypeName())));
	}

	private static List<String> messages(Type type) {
		Class<?> erasure = type instanceof Class<?> raw ? raw : (Class<?>) ((DataParameterizedType) type).getRawType();
		try {
			DataBindContext.builder().build().getDescriptor(erasure, type);
			return List.of("described");
		} catch (Exception e) {
			List<String> messages = new ArrayList<>();
			for (Throwable at = e; at != null; at = at.getCause()) {
				messages.add(at.getMessage());
			}
			return messages;
		}
	}
}
