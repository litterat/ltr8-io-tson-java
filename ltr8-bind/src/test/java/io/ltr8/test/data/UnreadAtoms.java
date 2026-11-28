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
import io.ltr8.annotation.Union;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * {@code @Atom} on a class described as something other than an atom, or on a super class's static method.
 */
public final class UnreadAtoms {

	private UnreadAtoms() {
	}

	public record Label(String text) {
	}

	// --- a union

	public sealed interface UnionWithFactory {

		@Atom
		static UnionWithFactory of(String text) {
			return new FactoryMember(text);
		}
	}

	public record FactoryMember(String text) implements UnionWithFactory {
	}

	@Atom
	@Union({ Label.class })
	public interface UnionMarkedOnType {
	}

	public abstract static sealed class UnionWithAtomConstructor permits ConstructorMember {

		@Atom
		public UnionWithAtomConstructor(String text) {
		}
	}

	public static final class ConstructorMember extends UnionWithAtomConstructor {

		@Record
		public ConstructorMember(String text) {
			super(text);
		}
	}

	public abstract static sealed class UnionWithAccessor permits AccessorMember {

		private final String code;

		protected UnionWithAccessor(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	public static final class AccessorMember extends UnionWithAccessor {

		@Record
		public AccessorMember(String code) {
			super(code);
		}
	}

	/** Its member is an atom, whose way out is the union's marked method. */
	public abstract static sealed class UnionSharingAWayOut permits SharedWayOutMember {

		private final String code;

		protected UnionSharingAWayOut(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	public static final class SharedWayOutMember extends UnionSharingAWayOut {

		@Atom
		public SharedWayOutMember(String code) {
			super(code);
		}
	}

	// --- an enum

	public enum EnumWithAccessor {
		RED;

		@Atom
		public String code() {
			return name();
		}
	}

	public enum EnumWithFactory {
		RED;

		@Atom
		public static EnumWithFactory of(String code) {
			return valueOf(code);
		}
	}

	public enum EnumWithConstructor {
		RED("r");

		private final String code;

		@Atom
		EnumWithConstructor(String code) {
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	public enum EnumBodyWithAccessor {
		RED {
			@Atom
			public String code() {
				return "r";
			}
		}
	}

	// --- a map, a collection or a bridged class

	public static final class MapWithAccessor<K, V> extends HashMap<K, V> {

		@Atom
		public String text() {
			return toString();
		}
	}

	public static final class CollectionWithAccessor<E> extends ArrayList<E> {

		@Atom
		public String text() {
			return toString();
		}
	}

	public static final class BridgedWithAccessor implements ToData<Label> {

		private final String text;

		public BridgedWithAccessor(Label label) {
			this.text = label.text();
		}

		@Override
		public Label toData() {
			return new Label(text);
		}

		@Atom
		public String text() {
			return text;
		}
	}

	// --- a super class's static way in

	public static class AtomBase {

		private final String code;

		public AtomBase(String code) {
			this.code = code;
		}

		@Atom
		public static AtomBase of(String code) {
			return new AtomBase(code);
		}

		@Atom
		public String code() {
			return code;
		}
	}

	public static final class AtomExtendingAtomBase extends AtomBase {

		@Atom
		public AtomExtendingAtomBase(String code) {
			super(code);
		}
	}

	public static final class RecordExtendingAtomBase extends AtomBase {

		@Record
		public RecordExtendingAtomBase(String code) {
			super(code);
		}
	}

	public abstract static sealed class UnionExtendingAtomBase extends AtomBase permits UnionExtendingAtomBaseMember {

		protected UnionExtendingAtomBase(String code) {
			super(code);
		}
	}

	public static final class UnionExtendingAtomBaseMember extends UnionExtendingAtomBase {

		@Record
		public UnionExtendingAtomBaseMember(String code) {
			super(code);
		}
	}

	public static final class BridgedExtendingAtomBase extends AtomBase implements ToData<Label> {

		public BridgedExtendingAtomBase(Label label) {
			super(label.text());
		}

		@Override
		public Label toData() {
			return new Label(code());
		}
	}
}
