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
package io.ltr8.bind.analysis;

import io.ltr8.bind.analysis.ClassSignals.Signal;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static io.ltr8.bind.analysis.ClassSignals.Signal.ABSTRACT;
import static io.ltr8.bind.analysis.ClassSignals.Signal.ANNOTATED_BOX;
import static io.ltr8.bind.analysis.ClassSignals.Signal.ARRAY;
import static io.ltr8.bind.analysis.ClassSignals.Signal.ATOM_ON_CONSTRUCTOR;
import static io.ltr8.bind.analysis.ClassSignals.Signal.ATOM_ON_INSTANCE_METHOD;
import static io.ltr8.bind.analysis.ClassSignals.Signal.ATOM_ON_STATIC_METHOD;
import static io.ltr8.bind.analysis.ClassSignals.Signal.ATOM_ON_TYPE;
import static io.ltr8.bind.analysis.ClassSignals.Signal.COLLECTION;
import static io.ltr8.bind.analysis.ClassSignals.Signal.ENUM;
import static io.ltr8.bind.analysis.ClassSignals.Signal.INTERFACE;
import static io.ltr8.bind.analysis.ClassSignals.Signal.JDK_RECORD;
import static io.ltr8.bind.analysis.ClassSignals.Signal.MAP;
import static io.ltr8.bind.analysis.ClassSignals.Signal.PRIMITIVE;
import static io.ltr8.bind.analysis.ClassSignals.Signal.RECORD_ON_CONSTRUCTOR;
import static io.ltr8.bind.analysis.ClassSignals.Signal.RECORD_ON_TYPE;
import static io.ltr8.bind.analysis.ClassSignals.Signal.SEALED;
import static io.ltr8.bind.analysis.ClassSignals.Signal.TO_DATA;
import static io.ltr8.bind.analysis.ClassSignals.Signal.TRANSPARENT;
import static io.ltr8.bind.analysis.ClassSignals.Signal.TUPLE;
import static io.ltr8.bind.analysis.ClassSignals.Signal.UNION;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KindTest {

	private static Kind of(Signal... signals) {
		return Kind.of(Set.of(signals));
	}

	private static Kind asAtom(Signal... signals) {
		return Kind.asAtom(Set.of(signals));
	}

	@Test
	void noSignalIsNoKind() {
		assertEquals(Kind.NONE, of());
	}

	@Test
	void annotatedIsTheBoxThoughItIsARecord() {
		assertEquals(Kind.ANNOTATED, of(ANNOTATED_BOX, JDK_RECORD));
	}

	@Test
	void transparentRecordIsTransparent() {
		assertEquals(Kind.TRANSPARENT, of(TRANSPARENT, JDK_RECORD));
		assertEquals(Kind.TRANSPARENT, of(TRANSPARENT, JDK_RECORD, TO_DATA));
	}

	@Test
	void transparentWinsOverTuple() {
		assertEquals(Kind.TRANSPARENT, of(TRANSPARENT, TUPLE, JDK_RECORD));
	}

	@Test
	void tupleRecordIsTuple() {
		assertEquals(Kind.TUPLE, of(TUPLE, JDK_RECORD));
		assertEquals(Kind.TUPLE, of(TUPLE, JDK_RECORD, RECORD_ON_TYPE, TO_DATA));
	}

	@Test
	void tupleOnAClassNotARecordIsStillTuple() {
		assertEquals(Kind.TUPLE, of(TUPLE));
		assertEquals(Kind.TRANSPARENT, of(TRANSPARENT));
	}

	@Test
	void recordIsAJdkRecordOrMarkedOnTypeOrPublicConstructor() {
		assertEquals(Kind.RECORD, of(JDK_RECORD));
		assertEquals(Kind.RECORD, of(RECORD_ON_TYPE));
		assertEquals(Kind.RECORD, of(RECORD_ON_CONSTRUCTOR));
	}

	@Test
	void recordImplementingToDataIsBridged() {
		assertEquals(Kind.BRIDGED, of(JDK_RECORD, TO_DATA));
		assertEquals(Kind.BRIDGED, of(RECORD_ON_TYPE, TO_DATA));
		assertEquals(Kind.BRIDGED, of(RECORD_ON_CONSTRUCTOR, TO_DATA));
	}

	@Test
	void recordMarkingAtomIsARecord() {
		assertEquals(Kind.RECORD, of(JDK_RECORD, ATOM_ON_CONSTRUCTOR));
		assertEquals(Kind.RECORD, of(RECORD_ON_TYPE, ATOM_ON_TYPE));
		assertEquals(Kind.BRIDGED, of(JDK_RECORD, TO_DATA, ATOM_ON_STATIC_METHOD));
	}

	@Test
	void toDataMarkingNoWayInIsBridged() {
		assertEquals(Kind.BRIDGED, of(TO_DATA));
		assertEquals(Kind.BRIDGED, of(TO_DATA, ATOM_ON_INSTANCE_METHOD));
		assertEquals(Kind.BRIDGED, of(TO_DATA, ABSTRACT, INTERFACE));
	}

	@Test
	void toDataMarkingAtomIsAnAtom() {
		assertEquals(Kind.ATOM, of(TO_DATA, ATOM_ON_TYPE));
		assertEquals(Kind.ATOM, of(TO_DATA, ATOM_ON_CONSTRUCTOR));
		assertEquals(Kind.ATOM, of(TO_DATA, ATOM_ON_STATIC_METHOD));
	}

	@Test
	void enumNeedsNoMark() {
		assertEquals(Kind.ENUM, of(ENUM));
		assertEquals(Kind.ENUM, of(ENUM, ATOM_ON_TYPE));
		assertEquals(Kind.ENUM, of(ENUM, ATOM_ON_CONSTRUCTOR));
	}

	@Test
	void enumTheJdkSealsIsAnEnumNotAUnion() {
		assertEquals(Kind.ENUM, of(ENUM, SEALED));
	}

	@Test
	void enumImplementingToDataIsAnEnum() {
		assertEquals(Kind.ENUM, of(ENUM, TO_DATA));
		assertEquals(Kind.ENUM, of(ENUM, TO_DATA, ATOM_ON_TYPE));
	}

	@Test
	void enumMarkedRecordIsAnEnum() {
		assertEquals(Kind.ENUM, of(ENUM, RECORD_ON_TYPE));
	}

	@Test
	void unionIsSealedOrAbstractAndMarked() {
		assertEquals(Kind.UNION, of(SEALED, INTERFACE, ABSTRACT));
		assertEquals(Kind.UNION, of(SEALED, ABSTRACT));
		assertEquals(Kind.UNION, of(SEALED));
		assertEquals(Kind.UNION, of(UNION, INTERFACE, ABSTRACT));
		assertEquals(Kind.UNION, of(UNION, ABSTRACT));
	}

	@Test
	void unmarkedAbstractOrMarkedConcreteIsNoUnion() {
		assertEquals(Kind.NONE, of(INTERFACE, ABSTRACT));
		assertEquals(Kind.NONE, of(ABSTRACT));
		assertEquals(Kind.NONE, of(UNION));
	}

	@Test
	void sealedOrMarkedCollectionIsAUnion() {
		assertEquals(Kind.UNION, of(SEALED, INTERFACE, ABSTRACT, COLLECTION));
		assertEquals(Kind.UNION, of(UNION, INTERFACE, ABSTRACT, COLLECTION));
	}

	@Test
	void sealedOrMarkedMapIsAUnion() {
		assertEquals(Kind.UNION, of(SEALED, INTERFACE, ABSTRACT, MAP));
		assertEquals(Kind.UNION, of(UNION, ABSTRACT, MAP));
	}

	@Test
	void unionWinsOverAtomMarks() {
		assertEquals(Kind.UNION, of(SEALED, INTERFACE, ABSTRACT, ATOM_ON_STATIC_METHOD));
		assertEquals(Kind.UNION, of(UNION, ABSTRACT, ATOM_ON_TYPE));
	}

	@Test
	void atomIsAPrimitiveOrMarkedOnTypeOrWayIn() {
		assertEquals(Kind.ATOM, of(PRIMITIVE));
		assertEquals(Kind.ATOM, of(ATOM_ON_TYPE));
		assertEquals(Kind.ATOM, of(ATOM_ON_CONSTRUCTOR));
		assertEquals(Kind.ATOM, of(ATOM_ON_STATIC_METHOD));
		assertEquals(Kind.ATOM, of(ATOM_ON_STATIC_METHOD, ABSTRACT));
	}

	@Test
	void markedWayOutAloneDecidesNothing() {
		assertEquals(Kind.NONE, of(ATOM_ON_INSTANCE_METHOD));
		assertEquals(Kind.RECORD, of(JDK_RECORD, ATOM_ON_INSTANCE_METHOD));
		assertEquals(Kind.ARRAY, of(COLLECTION, ATOM_ON_INSTANCE_METHOD));
	}

	@Test
	void atomWinsOverMapAndCollection() {
		assertEquals(Kind.ATOM, of(MAP, ATOM_ON_CONSTRUCTOR));
		assertEquals(Kind.ATOM, of(COLLECTION, ATOM_ON_STATIC_METHOD));
	}

	@Test
	void mapWinsOverCollection() {
		assertEquals(Kind.MAP, of(MAP));
		assertEquals(Kind.MAP, of(MAP, COLLECTION));
	}

	@Test
	void arrayIsAnArrayOrACollection() {
		assertEquals(Kind.ARRAY, of(ARRAY, ABSTRACT));
		assertEquals(Kind.ARRAY, of(COLLECTION));
		assertEquals(Kind.ARRAY, of(COLLECTION, INTERFACE, ABSTRACT));
	}

	@Test
	void recordMarkingAtomIsAnAtomWhenRegistered() {
		assertEquals(Kind.ATOM, asAtom(JDK_RECORD, ATOM_ON_CONSTRUCTOR));
		assertEquals(Kind.ATOM, asAtom(TUPLE, JDK_RECORD, ATOM_ON_STATIC_METHOD));
		assertEquals(Kind.ATOM, asAtom(SEALED, INTERFACE, ABSTRACT, ATOM_ON_STATIC_METHOD));
	}

	@Test
	void enumIsAnEnumWhenRegistered() {
		assertEquals(Kind.ENUM, asAtom(ENUM));
		assertEquals(Kind.ENUM, asAtom(ENUM, TO_DATA));
		assertEquals(Kind.ENUM, asAtom(ENUM, RECORD_ON_TYPE, ATOM_ON_TYPE));
	}

	@Test
	void classMarkingNoAtomIsNoAtomWhenRegistered() {
		assertEquals(Kind.NONE, asAtom(JDK_RECORD));
		assertEquals(Kind.NONE, asAtom(TO_DATA));
		assertEquals(Kind.NONE, asAtom(ATOM_ON_INSTANCE_METHOD));
		assertEquals(Kind.ATOM, asAtom(PRIMITIVE));
	}
}
