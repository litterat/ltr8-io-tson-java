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

import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Record;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One generic component of each shape -- a parameterized type, nested, a bare type variable, an array of one, a
 * wildcard, an {@code Optional}, a bridged variable -- on each way a class exposes a component: a record's canonical
 * constructor ({@code Rec}), a record's {@code @Profile} constructor ({@code Prof}), a {@code @Record} constructor
 * storing it ({@code Ctor}) or only naming it with {@code @Field} ({@code Named}), a public field ({@code Pub}), a
 * getter/setter pair ({@code Pair}), and a constructor component a setter writes too ({@code Merged}). A
 * {@code Text} class binds the variable of the class it extends to {@code String}; a {@code Holds} record holds one
 * at {@code String}.
 */
public final class GenericComponents {

	private GenericComponents() {
	}

	public static class SameText implements DataBridge<String, String> {

		@Override
		public String toData(String text) {
			return text;
		}

		@Override
		public String toObject(String text) {
			return text;
		}
	}

	// --- a record's canonical constructor

	public record RecList<T>(List<T> value) {
	}

	public record RecMap<T>(Map<String, T> value) {
	}

	public record RecNested<T>(List<List<T>> value) {
	}

	public record RecBare<T>(T value) {
	}

	public record RecArray<T>(T[] value) {
	}

	public record RecWild<T>(List<? extends T> value) {
	}

	public record RecOpt<T>(Optional<T> value) {
	}

	public record RecOptList<T>(Optional<List<T>> value) {
	}

	public record RecBridged<T>(@Field(bridge = SameText.class) T value) {
	}

	public record RecOptWild(Optional<? extends Number> value) {
	}

	public record HoldsRec(RecList<String> held) {
	}

	// --- a record's @Profile constructor

	public record ProfList<T>(List<T> value, int extra) {
		@Profile(value = "p", fields = "value")
		public ProfList(List<T> value) {
			this(value, 0);
		}
	}

	public record ProfMap<T>(Map<String, T> value, int extra) {
		@Profile(value = "p", fields = "value")
		public ProfMap(Map<String, T> value) {
			this(value, 0);
		}
	}

	public record ProfNested<T>(List<List<T>> value, int extra) {
		@Profile(value = "p", fields = "value")
		public ProfNested(List<List<T>> value) {
			this(value, 0);
		}
	}

	public record ProfBare<T>(T value, int extra) {
		@Profile(value = "p", fields = "value")
		public ProfBare(T value) {
			this(value, 0);
		}
	}

	public record ProfArray<T>(T[] value, int extra) {
		@Profile(value = "p", fields = "value")
		public ProfArray(T[] value) {
			this(value, 0);
		}
	}

	public record ProfWild<T>(List<? extends T> value, int extra) {
		@Profile(value = "p", fields = "value")
		public ProfWild(List<? extends T> value) {
			this(value, 0);
		}
	}

	public record ProfOpt<T>(Optional<T> value, int extra) {
		@Profile(value = "p", fields = "value")
		public ProfOpt(Optional<T> value) {
			this(value, 0);
		}
	}

	public record ProfOptList<T>(Optional<List<T>> value, int extra) {
		@Profile(value = "p", fields = "value")
		public ProfOptList(Optional<List<T>> value) {
			this(value, 0);
		}
	}

	public record ProfBridged<T>(@Field(bridge = SameText.class) T value, int extra) {
		@Profile(value = "p", fields = "value")
		public ProfBridged(T value) {
			this(value, 0);
		}
	}

	public record HoldsProf(ProfList<String> held) {
	}

	// --- a @Record constructor storing its parameter

	public static class CtorList<T> {
		private final List<T> value;

		@Record
		public CtorList(List<T> value) {
			this.value = value;
		}

		public List<T> getValue() {
			return value;
		}
	}

	public static class CtorMap<T> {
		private final Map<String, T> value;

		@Record
		public CtorMap(Map<String, T> value) {
			this.value = value;
		}

		public Map<String, T> getValue() {
			return value;
		}
	}

	public static class CtorNested<T> {
		private final List<List<T>> value;

		@Record
		public CtorNested(List<List<T>> value) {
			this.value = value;
		}

		public List<List<T>> getValue() {
			return value;
		}
	}

	public static class CtorBare<T> {
		private final T value;

		@Record
		public CtorBare(T value) {
			this.value = value;
		}

		public T getValue() {
			return value;
		}
	}

	public static class CtorArray<T> {
		private final T[] value;

		@Record
		public CtorArray(T[] value) {
			this.value = value;
		}

		public T[] getValue() {
			return value;
		}
	}

	public static class CtorWild<T> {
		private final List<? extends T> value;

		@Record
		public CtorWild(List<? extends T> value) {
			this.value = value;
		}

		public List<? extends T> getValue() {
			return value;
		}
	}

	public static class CtorOpt<T> {
		private final Optional<T> value;

		@Record
		public CtorOpt(Optional<T> value) {
			this.value = value;
		}

		public Optional<T> getValue() {
			return value;
		}
	}

	public static class CtorOptList<T> {
		private final Optional<List<T>> value;

		@Record
		public CtorOptList(Optional<List<T>> value) {
			this.value = value;
		}

		public Optional<List<T>> getValue() {
			return value;
		}
	}

	public static class CtorBridged<T> {
		@Field(bridge = SameText.class)
		private final T value;

		@Record
		public CtorBridged(T value) {
			this.value = value;
		}

		public T getValue() {
			return value;
		}
	}

	public static class TextCtorList extends CtorList<String> {
		@Record
		public TextCtorList(List<String> value) {
			super(value);
		}
	}

	public static class TextCtorMap extends CtorMap<String> {
		@Record
		public TextCtorMap(Map<String, String> value) {
			super(value);
		}
	}

	public static class TextCtorNested extends CtorNested<String> {
		@Record
		public TextCtorNested(List<List<String>> value) {
			super(value);
		}
	}

	public static class TextCtorBare extends CtorBare<String> {
		@Record
		public TextCtorBare(String value) {
			super(value);
		}
	}

	public static class TextCtorArray extends CtorArray<String> {
		@Record
		public TextCtorArray(String[] value) {
			super(value);
		}
	}

	public static class TextCtorWild extends CtorWild<String> {
		@Record
		public TextCtorWild(List<? extends String> value) {
			super(value);
		}
	}

	public static class TextCtorOpt extends CtorOpt<String> {
		@Record
		public TextCtorOpt(Optional<String> value) {
			super(value);
		}
	}

	public static class TextCtorOptList extends CtorOptList<String> {
		@Record
		public TextCtorOptList(Optional<List<String>> value) {
			super(value);
		}
	}

	public static class TextCtorBridged extends CtorBridged<String> {
		@Record
		public TextCtorBridged(String value) {
			super(value);
		}
	}

	/** A subclass passing its own type variable up: the constructor parameter is the subclass's {@code List<U>}. */
	public static class PassedCtorList<U> extends CtorList<U> {
		@Record
		public PassedCtorList(List<U> value) {
			super(value);
		}
	}

	public record HoldsCtor(CtorList<String> held) {
	}

	// --- a @Record constructor naming, not storing, its parameter

	public static class NamedList<T> {
		private final List<T> value;

		@Record
		public NamedList(@Field("value") List<T> value) {
			this.value = List.copyOf(value);
		}

		public List<T> getValue() {
			return value;
		}
	}

	public static class NamedMap<T> {
		private final Map<String, T> value;

		@Record
		public NamedMap(@Field("value") Map<String, T> value) {
			this.value = Map.copyOf(value);
		}

		public Map<String, T> getValue() {
			return value;
		}
	}

	public static class NamedBare<T> {
		private final T value;

		@Record
		public NamedBare(@Field("value") T value) {
			this.value = java.util.Objects.requireNonNull(value);
		}

		public T getValue() {
			return value;
		}
	}

	public static class NamedOpt<T> {
		private final Optional<T> value;

		@Record
		public NamedOpt(@Field("value") Optional<T> value) {
			this.value = java.util.Objects.requireNonNull(value);
		}

		public Optional<T> getValue() {
			return value;
		}
	}

	public record HoldsNamed(NamedList<String> held) {
	}

	// --- a public field

	public static class PubList<T> {
		public List<T> value;

		@Record
		public PubList() {
		}
	}

	public static class PubMap<T> {
		public Map<String, T> value;

		@Record
		public PubMap() {
		}
	}

	public static class PubNested<T> {
		public List<List<T>> value;

		@Record
		public PubNested() {
		}
	}

	public static class PubBare<T> {
		public T value;

		@Record
		public PubBare() {
		}
	}

	public static class PubArray<T> {
		public T[] value;

		@Record
		public PubArray() {
		}
	}

	public static class PubWild<T> {
		public List<? extends T> value;

		@Record
		public PubWild() {
		}
	}

	public static class PubOpt<T> {
		public Optional<T> value;

		@Record
		public PubOpt() {
		}
	}

	public static class PubOptList<T> {
		public Optional<List<T>> value;

		@Record
		public PubOptList() {
		}
	}

	public static class PubBridged<T> {
		@Field(bridge = SameText.class)
		public T value;

		@Record
		public PubBridged() {
		}
	}

	public static class TextPubList extends PubList<String> {
		@Record
		public TextPubList() {
		}
	}

	public static class TextPubMap extends PubMap<String> {
		@Record
		public TextPubMap() {
		}
	}

	public static class TextPubNested extends PubNested<String> {
		@Record
		public TextPubNested() {
		}
	}

	public static class TextPubBare extends PubBare<String> {
		@Record
		public TextPubBare() {
		}
	}

	public static class TextPubArray extends PubArray<String> {
		@Record
		public TextPubArray() {
		}
	}

	public static class TextPubWild extends PubWild<String> {
		@Record
		public TextPubWild() {
		}
	}

	public static class TextPubOpt extends PubOpt<String> {
		@Record
		public TextPubOpt() {
		}
	}

	public static class TextPubOptList extends PubOptList<String> {
		@Record
		public TextPubOptList() {
		}
	}

	public static class TextPubBridged extends PubBridged<String> {
		@Record
		public TextPubBridged() {
		}
	}

	/** Binds the variable of the class it extends to its own: described at an argument, the field is at that. */
	public static class PassedPubList<U> extends PubList<U> {
		@Record
		public PassedPubList() {
		}
	}

	/** Binds the variable to a parameterized type: the field's bare variable becomes that type. */
	public static class ListPubBare extends PubBare<List<String>> {
		@Record
		public ListPubBare() {
		}
	}

	public record HoldsPub(PubList<String> held) {
	}

	// --- a getter/setter pair

	public static class PairList<T> {
		private List<T> value;

		@Record
		public PairList() {
		}

		public List<T> getValue() {
			return value;
		}

		public void setValue(List<T> value) {
			this.value = value;
		}
	}

	public static class PairMap<T> {
		private Map<String, T> value;

		@Record
		public PairMap() {
		}

		public Map<String, T> getValue() {
			return value;
		}

		public void setValue(Map<String, T> value) {
			this.value = value;
		}
	}

	public static class PairNested<T> {
		private List<List<T>> value;

		@Record
		public PairNested() {
		}

		public List<List<T>> getValue() {
			return value;
		}

		public void setValue(List<List<T>> value) {
			this.value = value;
		}
	}

	public static class PairBare<T> {
		private T value;

		@Record
		public PairBare() {
		}

		public T getValue() {
			return value;
		}

		public void setValue(T value) {
			this.value = value;
		}
	}

	public static class PairArray<T> {
		private T[] value;

		@Record
		public PairArray() {
		}

		public T[] getValue() {
			return value;
		}

		public void setValue(T[] value) {
			this.value = value;
		}
	}

	public static class PairWild<T> {
		private List<? extends T> value;

		@Record
		public PairWild() {
		}

		public List<? extends T> getValue() {
			return value;
		}

		public void setValue(List<? extends T> value) {
			this.value = value;
		}
	}

	public static class PairOpt<T> {
		private Optional<T> value;

		@Record
		public PairOpt() {
		}

		public Optional<T> getValue() {
			return value;
		}

		public void setValue(Optional<T> value) {
			this.value = value;
		}
	}

	public static class PairOptList<T> {
		private Optional<List<T>> value;

		@Record
		public PairOptList() {
		}

		public Optional<List<T>> getValue() {
			return value;
		}

		public void setValue(Optional<List<T>> value) {
			this.value = value;
		}
	}

	public static class PairBridged<T> {
		private T value;

		@Record
		public PairBridged() {
		}

		@Field(bridge = SameText.class)
		public T getValue() {
			return value;
		}

		public void setValue(T value) {
			this.value = value;
		}
	}

	public static class PairOptWild {
		private Optional<? extends Number> value;

		@Record
		public PairOptWild() {
		}

		public Optional<? extends Number> getValue() {
			return value;
		}

		public void setValue(Optional<? extends Number> value) {
			this.value = value;
		}
	}

	/** A setter taking the type the class is described at, not its getter's: no pair, at any description. */
	public static class PairSetterAtArgument<T> {
		private List<T> value;

		@Record
		public PairSetterAtArgument() {
		}

		public List<T> getValue() {
			return value;
		}

		@SuppressWarnings("unchecked")
		public void setValue(List<String> value) {
			this.value = (List<T>) value;
		}
	}

	public static class TextPairList extends PairList<String> {
		@Record
		public TextPairList() {
		}
	}

	public static class TextPairMap extends PairMap<String> {
		@Record
		public TextPairMap() {
		}
	}

	public static class TextPairNested extends PairNested<String> {
		@Record
		public TextPairNested() {
		}
	}

	public static class TextPairBare extends PairBare<String> {
		@Record
		public TextPairBare() {
		}
	}

	public static class TextPairArray extends PairArray<String> {
		@Record
		public TextPairArray() {
		}
	}

	public static class TextPairWild extends PairWild<String> {
		@Record
		public TextPairWild() {
		}
	}

	public static class TextPairOpt extends PairOpt<String> {
		@Record
		public TextPairOpt() {
		}
	}

	public static class TextPairOptList extends PairOptList<String> {
		@Record
		public TextPairOptList() {
		}
	}

	public static class TextPairBridged extends PairBridged<String> {
		@Record
		public TextPairBridged() {
		}
	}

	public static class PassedPairList<U> extends PairList<U> {
		@Record
		public PassedPairList() {
		}
	}

	public static class ListPairBare extends PairBare<List<String>> {
		@Record
		public ListPairBare() {
		}
	}

	public record HoldsPair(PairList<String> held) {
	}

	// --- a constructor component a setter writes too

	public static class MergedList<T> {
		private List<T> value;

		@Record
		public MergedList(List<T> value) {
			this.value = value;
		}

		public List<T> getValue() {
			return value;
		}

		public void setValue(List<T> value) {
			this.value = value;
		}
	}

	public static class MergedMap<T> {
		private Map<String, T> value;

		@Record
		public MergedMap(Map<String, T> value) {
			this.value = value;
		}

		public Map<String, T> getValue() {
			return value;
		}

		public void setValue(Map<String, T> value) {
			this.value = value;
		}
	}

	public static class MergedNested<T> {
		private List<List<T>> value;

		@Record
		public MergedNested(List<List<T>> value) {
			this.value = value;
		}

		public List<List<T>> getValue() {
			return value;
		}

		public void setValue(List<List<T>> value) {
			this.value = value;
		}
	}

	public static class MergedBare<T> {
		private T value;

		@Record
		public MergedBare(T value) {
			this.value = value;
		}

		public T getValue() {
			return value;
		}

		public void setValue(T value) {
			this.value = value;
		}
	}

	public static class MergedArray<T> {
		private T[] value;

		@Record
		public MergedArray(T[] value) {
			this.value = value;
		}

		public T[] getValue() {
			return value;
		}

		public void setValue(T[] value) {
			this.value = value;
		}
	}

	public static class MergedWild<T> {
		private List<? extends T> value;

		@Record
		public MergedWild(List<? extends T> value) {
			this.value = value;
		}

		public List<? extends T> getValue() {
			return value;
		}

		public void setValue(List<? extends T> value) {
			this.value = value;
		}
	}

	public static class MergedOpt<T> {
		private Optional<T> value;

		@Record
		public MergedOpt(Optional<T> value) {
			this.value = value;
		}

		public Optional<T> getValue() {
			return value;
		}

		public void setValue(Optional<T> value) {
			this.value = value;
		}
	}

	public static class MergedOptList<T> {
		private Optional<List<T>> value;

		@Record
		public MergedOptList(Optional<List<T>> value) {
			this.value = value;
		}

		public Optional<List<T>> getValue() {
			return value;
		}

		public void setValue(Optional<List<T>> value) {
			this.value = value;
		}
	}

	public static class MergedBridged<T> {
		@Field(bridge = SameText.class)
		private T value;

		@Record
		public MergedBridged(T value) {
			this.value = value;
		}

		public T getValue() {
			return value;
		}

		public void setValue(T value) {
			this.value = value;
		}
	}

	public static class TextMergedList extends MergedList<String> {
		@Record
		public TextMergedList(List<String> value) {
			super(value);
		}
	}

	public static class TextMergedMap extends MergedMap<String> {
		@Record
		public TextMergedMap(Map<String, String> value) {
			super(value);
		}
	}

	public static class TextMergedNested extends MergedNested<String> {
		@Record
		public TextMergedNested(List<List<String>> value) {
			super(value);
		}
	}

	public static class TextMergedBare extends MergedBare<String> {
		@Record
		public TextMergedBare(String value) {
			super(value);
		}
	}

	public static class TextMergedArray extends MergedArray<String> {
		@Record
		public TextMergedArray(String[] value) {
			super(value);
		}
	}

	public static class TextMergedWild extends MergedWild<String> {
		@Record
		public TextMergedWild(List<? extends String> value) {
			super(value);
		}
	}

	public static class TextMergedOpt extends MergedOpt<String> {
		@Record
		public TextMergedOpt(Optional<String> value) {
			super(value);
		}
	}

	public static class TextMergedOptList extends MergedOptList<String> {
		@Record
		public TextMergedOptList(Optional<List<String>> value) {
			super(value);
		}
	}

	public static class TextMergedBridged extends MergedBridged<String> {
		@Record
		public TextMergedBridged(String value) {
			super(value);
		}
	}

	/** A raw constructor parameter whose setter, inherited, is at the bound argument. */
	public static class RawCtorTextSetter extends MergedList<String> {
		@SuppressWarnings({ "rawtypes", "unchecked" })
		@Record
		public RawCtorTextSetter(List value) {
			super(value);
		}
	}

	public record HoldsMerged(MergedList<String> held) {
	}

	// --- a bare type variable, and an array of one, at an argument

	/** An accessor the constructor analysis cannot follow, found by its {@code @Field}. */
	public static class FieldAccessorBare<T> {
		private final T value;

		@Record
		public FieldAccessorBare(T value) {
			this.value = value;
		}

		@Field
		public T value() {
			return java.util.Objects.requireNonNull(value);
		}
	}

	public record HoldsBares(RecBare<String> rec, RecArray<String> recArray, CtorBare<String> ctor,
			CtorArray<String> ctorArray, FieldAccessorBare<String> fieldAccessor, PubBare<String> pub,
			PubArray<String> pubArray, PairBare<String> pair, PairArray<String> pairArray, MergedBare<String> merged,
			MergedArray<String> mergedArray, PubBare<List<String>> pubList) {
	}

	public record HoldsProfBares(ProfBare<String> bare, ProfArray<String> array) {
	}
}
