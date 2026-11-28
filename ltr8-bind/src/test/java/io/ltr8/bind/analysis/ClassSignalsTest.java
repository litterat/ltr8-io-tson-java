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

import io.ltr8.annotation.Atom;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Union;
import io.ltr8.bind.analysis.ClassSignals.Signal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassSignalsTest {

	public static class RecordOnPublicConstructor {
		@Record
		public RecordOnPublicConstructor() {
		}
	}

	public static class RecordOnHiddenConstructor {
		@Record
		RecordOnHiddenConstructor() {
		}
	}

	@Union
	public interface MarkedInterface {
	}

	public static class Code {
		@Atom
		public static Code of(String value) {
			return new Code();
		}

		@Atom
		public String value() {
			return "";
		}
	}

	public static class SubCode extends Code {
	}

	@Test
	void recordOnAConstructorCountsOnlyAPublicOne() {
		assertTrue(ClassSignals.of(RecordOnPublicConstructor.class).has(Signal.RECORD_ON_CONSTRUCTOR));
		assertFalse(ClassSignals.of(RecordOnHiddenConstructor.class).has(Signal.RECORD_ON_CONSTRUCTOR));
	}

	@Test
	void interfaceIsAbstract() {
		assertTrue(ClassSignals.of(MarkedInterface.class).has(Signal.ABSTRACT));
		assertEquals(Kind.UNION, ClassSignals.of(MarkedInterface.class).kind());
	}

	@Test
	void wayOutIsInheritedAndStaticWayInIsNot() {
		ClassSignals signals = ClassSignals.of(SubCode.class);
		assertTrue(signals.has(Signal.ATOM_ON_INSTANCE_METHOD));
		assertFalse(signals.has(Signal.ATOM_ON_STATIC_METHOD));
		assertTrue(ClassSignals.of(Code.class).marksAtom());
	}
}
