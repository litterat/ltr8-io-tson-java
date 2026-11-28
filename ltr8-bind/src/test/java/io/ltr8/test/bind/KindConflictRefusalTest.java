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
import io.ltr8.test.data.KindConflicts.AbstractRecord;
import io.ltr8.test.data.KindConflicts.AbstractRecordConstructor;
import io.ltr8.test.data.KindConflicts.AbstractRecordUnion;
import io.ltr8.test.data.KindConflicts.RecordInterface;
import io.ltr8.test.data.KindConflicts.SealedAbstractRecord;
import io.ltr8.test.data.KindConflicts.TransparentRecord;
import io.ltr8.test.data.KindConflicts.TransparentRecordConstructor;
import io.ltr8.test.data.KindConflicts.TransparentToData;
import io.ltr8.test.data.KindConflicts.TransparentTuple;
import io.ltr8.test.data.KindConflicts.TupleRecord;
import io.ltr8.test.data.KindConflicts.TupleToData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

	/** Described in two fresh contexts, {@code type}'s name and {@code refusal} are its whole message chain. */
	private static Executable described(Class<?> type, String refusal) {
		return () -> assertAll(Stream.of(1, 2).<Executable>map(run -> () -> assertEquals(
				List.of(type.getName() + " " + refusal), messages(type), type.getSimpleName())));
	}

	private static List<String> messages(Class<?> type) {
		List<String> messages = new ArrayList<>();
		try {
			DataBindContext.builder().build().getDescriptor(type);
			messages.add("described");
		} catch (Exception e) {
			for (Throwable at = e; at != null; at = at.getCause()) {
				messages.add(at.getMessage());
			}
		}
		return messages;
	}
}
