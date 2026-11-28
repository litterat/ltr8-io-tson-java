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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import io.ltr8.annotation.Atom;
import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.FieldOrder;
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

	// --- a union that is a container too

	public sealed interface SealedMap extends Map<String, String> permits SealedMapMember {
	}

	public static final class SealedMapMember extends HashMap<String, String> implements SealedMap {
	}

	@Union
	public interface UnionCollection extends Collection<String> {
	}

	@Union
	public static class UnionList extends ArrayList<String> {
	}

	// --- a record mark on a class its ToData describes

	@Record
	public static final class RecordToData implements ToData<Text> {
		private final String value;

		public RecordToData(Text text) {
			this.value = text.value();
		}

		@Override
		public Text toData() {
			return new Text(value);
		}
	}

	public static final class RecordConstructorToData implements ToData<Text> {
		private final String value;

		@Record
		public RecordConstructorToData(Text text) {
			this.value = text.value();
		}

		@Override
		public Text toData() {
			return new Text(value);
		}
	}

	// --- a record mark on a constructor nothing builds through

	public static class RecordOnHiddenConstructor {
		@Record
		RecordOnHiddenConstructor(String value) {
		}
	}

	public record RecordOnHiddenRecordConstructor(String value, int count) {
		@Record
		RecordOnHiddenRecordConstructor(String value) {
			this(value, 0);
		}
	}

	// --- an enum saying another kind

	public enum EnumToData implements ToData<String> {
		ONE;

		@Override
		public String toData() {
			return name().toLowerCase();
		}
	}

	@Atom
	public enum AtomEnumToData implements ToData<String> {
		ONE;

		@Override
		public String toData() {
			return name().toLowerCase();
		}
	}

	@Record
	public enum RecordEnum {
		ONE
	}

	// --- an order on a kind that has no components of its own

	@FieldOrder({})
	public enum OrderedEnum {
		ONE
	}

	@FieldOrder({})
	public sealed interface OrderedUnion permits OrderedUnionMember {
	}

	public record OrderedUnionMember(String value) implements OrderedUnion {
	}

	@FieldOrder({ "value" })
	public static final class OrderedAtom {
		private final String value;

		@Atom
		public OrderedAtom(String value) {
			this.value = value;
		}

		@Atom
		public String value() {
			return value;
		}
	}

	@FieldOrder({})
	public static class OrderedMap extends HashMap<String, String> {
	}

	@FieldOrder({})
	public static class OrderedList extends ArrayList<String> {
	}

	@FieldOrder({ "value" })
	public record OrderedRecord(String value) {
	}

	/** Registers {@link TupleRecord} as an atom of its one value, setting its marks aside. */
	public static final class TupleRecordAsText implements DataBridge<String, TupleRecord> {

		@Override
		public String toData(TupleRecord object) {
			return object.value();
		}

		@Override
		public TupleRecord toObject(String data) {
			return new TupleRecord(data);
		}
	}

	// --- a ToData naming a class that is no plain record

	@Tuple
	public record TupleText(String value) {
	}

	public static final class BridgedToTuple implements ToData<TupleText> {
		private final String value;

		public BridgedToTuple(TupleText text) {
			this.value = text.value();
		}

		@Override
		public TupleText toData() {
			return new TupleText(value);
		}
	}

	@Transparent
	public record TransparentText(String value) {
	}

	public static final class BridgedToTransparent implements ToData<TransparentText> {
		private final String value;

		public BridgedToTransparent(TransparentText text) {
			this.value = text.value();
		}

		@Override
		public TransparentText toData() {
			return new TransparentText(value);
		}
	}

	public static final class BridgedToAbstract implements ToData<AbstractRecord> {
		public BridgedToAbstract(AbstractRecord record) {
		}

		@Override
		public AbstractRecord toData() {
			return null;
		}
	}
}
