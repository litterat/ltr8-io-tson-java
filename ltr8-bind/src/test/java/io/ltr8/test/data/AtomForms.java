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
import io.ltr8.annotation.ToData;

import java.util.Map;

/**
 * Classes written as one value the wire carries, each saying its way in and its way out, and the ways of saying
 * them that an atom refuses.
 */
public final class AtomForms {

	private AtomForms() {
	}

	public record Holder<T>(T value) {
	}

	public record Keys<T>(Map<T, String> keys) {
	}

	// --- each way in with each way out

	public static final class ConstructorAndAccessor {

		private final String code;

		@Atom
		public ConstructorAndAccessor(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	public static final class ConstructorAndToData implements ToData<String> {

		private final String code;

		@Atom
		public ConstructorAndToData(String code) {
			this.code = code;
		}

		@Override
		public String toData() {
			return code;
		}
	}

	public static final class FactoryAndAccessor {

		private final int id;

		private FactoryAndAccessor(int id) {
			this.id = id;
		}

		@Atom
		public static FactoryAndAccessor of(int id) {
			return new FactoryAndAccessor(id);
		}

		@Atom
		public int id() {
			return id;
		}
	}

	public static final class FactoryAndToData implements ToData<Integer> {

		private final int id;

		private FactoryAndToData(int id) {
			this.id = id;
		}

		@Atom
		public static FactoryAndToData of(int id) {
			return new FactoryAndToData(id);
		}

		@Override
		public Integer toData() {
			return id;
		}
	}

	public static final class BoxedConstructorAndPrimitiveAccessor {

		private final int count;

		@Atom
		public BoxedConstructorAndPrimitiveAccessor(Integer count) {
			this.count = count;
		}

		@Atom
		public int count() {
			return count;
		}
	}

	public static final class MarkedToData implements ToData<String> {

		private final String code;

		@Atom
		public MarkedToData(String code) {
			this.code = code;
		}

		@Atom
		@Override
		public String toData() {
			return code;
		}
	}

	// --- inherited ways out

	public abstract static class TextBase implements ToData<String> {

		private final String text;

		protected TextBase(String text) {
			this.text = text;
		}

		@Override
		public String toData() {
			return text;
		}
	}

	public static final class InheritsToData extends TextBase {

		@Atom
		public InheritsToData(String text) {
			super(text);
		}
	}

	public abstract static class ValueBase<T> implements ToData<T> {

		private final T value;

		protected ValueBase(T value) {
			this.value = value;
		}

		@Override
		public T toData() {
			return value;
		}
	}

	public static final class InheritsToDataThroughATypeArgument extends ValueBase<Long> {

		@Atom
		public InheritsToDataThroughATypeArgument(long value) {
			super(value);
		}
	}

	public abstract static class CodeBase {

		private final String code;

		protected CodeBase(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	public static final class InheritsMarkedAccessor extends CodeBase {

		@Atom
		public InheritsMarkedAccessor(String code) {
			super(code);
		}
	}

	// --- enums

	public enum Plain { ONE, TWO }

	@Atom
	public enum Marked { ONE, TWO }

	// --- a record stays a record

	public record RecordWithAtomConstructor(String code) {

		@Atom
		public RecordWithAtomConstructor {
		}
	}

	// --- a ToData class with no way in marked stays a bridged record

	public static final class ToDataWithoutAtom implements ToData<String> {

		private final String code;

		public ToDataWithoutAtom(String code) {
			this.code = code;
		}

		@Override
		public String toData() {
			return code;
		}
	}

	// --- refused: no way out said

	public static final class BareToData {

		private final String code;

		@Atom
		public BareToData(String code) {
			this.code = code;
		}

		public String toData() {
			return code;
		}
	}

	public static final class FactoryWithoutWayOut {

		private FactoryWithoutWayOut() {
		}

		@Atom
		public static FactoryWithoutWayOut of(int id) {
			return new FactoryWithoutWayOut();
		}
	}

	// --- refused: no way in said

	@Atom
	public static final class TypeOnly {

		private final String code;

		public TypeOnly(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	@Atom
	public static final class ToDataTypeOnly implements ToData<String> {

		private final String code;

		public ToDataTypeOnly(String code) {
			this.code = code;
		}

		@Override
		public String toData() {
			return code;
		}
	}

	// --- refused: two ways in or two ways out

	public static final class TwoFactories {

		private final int id;

		private TwoFactories(int id) {
			this.id = id;
		}

		@Atom
		public static TwoFactories first(int id) {
			return new TwoFactories(id);
		}

		@Atom
		public static TwoFactories second(int id) {
			return new TwoFactories(id);
		}

		@Atom
		public int id() {
			return id;
		}
	}

	public static final class FactoryBesideConstructor {

		private final int id;

		@Atom
		public FactoryBesideConstructor(int id) {
			this.id = id;
		}

		@Atom
		public static FactoryBesideConstructor of(int id) {
			return new FactoryBesideConstructor(id);
		}

		@Atom
		public int id() {
			return id;
		}
	}

	public static final class TwoAccessors {

		private final String code;

		@Atom
		public TwoAccessors(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}

		@Atom
		public String label() {
			return code;
		}
	}

	public static final class AccessorBesideToData implements ToData<String> {

		private final String code;

		@Atom
		public AccessorBesideToData(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}

		@Override
		public String toData() {
			return code;
		}
	}

	// --- refused: the two halves disagree

	public static final class AccessorOfAnotherType {

		private final String code;

		@Atom
		public AccessorOfAnotherType(String code) {
			this.code = code;
		}

		@Atom
		public int length() {
			return code.length();
		}
	}

	public static final class ToDataOfAnotherType implements ToData<String> {

		private final int id;

		private ToDataOfAnotherType(int id) {
			this.id = id;
		}

		@Atom
		public static ToDataOfAnotherType of(int id) {
			return new ToDataOfAnotherType(id);
		}

		@Override
		public String toData() {
			return Integer.toString(id);
		}
	}

	public static final class ToDataOfARecord implements ToData<OutOfRange> {

		private final String code;

		@Atom
		public ToDataOfARecord(String code) {
			this.code = code;
		}

		@Override
		public OutOfRange toData() {
			return new OutOfRange(code);
		}
	}

	public record OutOfRange(String code) {
	}

	// --- refused: @Atom where it cannot apply

	public static final class FactoryReturningAnotherType {

		@Atom
		public static String of(int id) {
			return Integer.toString(id);
		}

		@Atom
		public int id() {
			return 0;
		}
	}

	public static final class AccessorTakingAParameter {

		private final String code;

		@Atom
		public AccessorTakingAParameter(String code) {
			this.code = code;
		}

		@Atom
		public String code(int from) {
			return code.substring(from);
		}
	}

	public abstract static class AbstractWithAtomConstructor {

		private final String code;

		@Atom
		public AbstractWithAtomConstructor(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	public static final class ConstructorNotPublic {

		private final String code;

		@Atom
		ConstructorNotPublic(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	@SuppressWarnings("rawtypes")
	public static final class RawToDataAtom implements ToData {

		private final String code;

		@Atom
		public RawToDataAtom(String code) {
			this.code = code;
		}

		@Override
		public Object toData() {
			return code;
		}
	}

	public static final class OpenToDataAtom<T> implements ToData<T> {

		@Atom
		public OpenToDataAtom(String code) {
		}

		@Override
		public T toData() {
			return null;
		}
	}
}
