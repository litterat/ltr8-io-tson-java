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
package io.ltr8.test.data;

import io.ltr8.annotation.Atom;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Tuple;
import io.ltr8.annotation.Union;

/** Classes whose marks say two kinds, or a mark the kind they are given never reads. */
public final class KindConflicts {

	private KindConflicts() {
	}

	public record Text(String value) {
	}

	// --- two ways to write a record

	@Transparent
	@Tuple
	public record TransparentTuple(String value) {
	}

	@Transparent
	public record TransparentToData(String value) implements ToData<Text> {
		public TransparentToData(Text text) {
			this(text.value());
		}

		@Override
		public Text toData() {
			return new Text(value);
		}
	}

	@Transparent
	@Record
	public record TransparentRecord(String value) {
	}

	@Transparent
	public record TransparentRecordConstructor(String value) {
		@Record
		public TransparentRecordConstructor {
		}
	}

	@Tuple
	public record TupleToData(String value) implements ToData<Text> {
		public TupleToData(Text text) {
			this(text.value());
		}

		@Override
		public Text toData() {
			return new Text(value);
		}
	}

	@Tuple
	@Record
	public record TupleRecord(String value) {
	}

	// --- a record nothing can build

	@Record
	public abstract static class AbstractRecord {
	}

	public abstract static class AbstractRecordConstructor {
		@Record
		public AbstractRecordConstructor(String value) {
		}
	}

	@Record
	@Union
	public abstract static class AbstractRecordUnion {
	}

	@Record
	public abstract static sealed class SealedAbstractRecord permits SealedAbstractRecordMember {
	}

	public static final class SealedAbstractRecordMember extends SealedAbstractRecord {
	}

	@Record
	public interface RecordInterface {
	}

	// --- a union mark on a class that is not one

	@Union
	public record RecordUnion(String value) {
	}

	@Union
	public enum EnumUnion {
		ONE
	}

	@Union
	public static final class AtomUnion {
		private final String value;

		@Atom
		public AtomUnion(String value) {
			this.value = value;
		}

		@Atom
		public String value() {
			return value;
		}
	}

	@Union
	public static class ConcreteUnion {
	}

	@Union
	public interface ToDataUnion extends ToData<Text> {
	}

	// --- a sealed union listing what permits lists

	@Union({ SealedListed.class })
	public sealed interface SealedListingUnion permits SealedListed {
	}

	public record SealedListed(String value) implements SealedListingUnion {
	}

	@Union(sealed = false)
	public sealed interface SealedOpenUnion permits SealedOpened {
	}

	public record SealedOpened(String value) implements SealedOpenUnion {
	}
}
