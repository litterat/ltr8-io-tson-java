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
import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Tuple;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Union;

import java.util.ArrayList;
import java.util.HashMap;

/** Each kind of descriptor, with and without a {@code @Typename} of its own. */
public final class Typenames {

	private Typenames() {
	}

	@Typename(name = "named_record")
	public record NamedRecord(String text) {
	}

	public record PlainRecord(String text) {
	}

	@Tuple
	@Typename(name = "named_pair")
	public record NamedPair(int x, int y) {
	}

	@Tuple
	public record PlainPair(int x, int y) {
	}

	@Typename(name = "named_code")
	public static final class NamedCode {
		private final String code;

		@Atom
		public NamedCode(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	public static final class PlainCode {
		private final String code;

		@Atom
		public PlainCode(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	/** Registered as an atom, declaring no atom form of its own. */
	@Typename(name = "named_token")
	public static final class NamedToken {
	}

	public static final class PlainToken {
	}

	@Typename(name = "named_colour")
	public enum NamedColour {
		RED
	}

	public enum PlainColour {
		RED
	}

	@Typename(name = "named_shape")
	public sealed interface NamedShape permits Square {
	}

	public sealed interface PlainShape permits Disc {
	}

	@Typename(name = "square")
	public record Square(int side) implements NamedShape {
	}

	public record Disc(int radius) implements PlainShape {
	}

	public record Peg(int size) {
	}

	public record HoldsEmbeddedUnion(@Union({ Peg.class }) Object slot) {
	}

	@SuppressWarnings("serial")
	@Typename(name = "named_bag")
	public static class NamedBag<T> extends ArrayList<T> {
	}

	@SuppressWarnings("serial")
	public static class PlainBag<T> extends ArrayList<T> {
	}

	@SuppressWarnings("serial")
	@Typename(name = "named_table")
	public static class NamedTable<K, V> extends HashMap<K, V> {
	}

	@SuppressWarnings("serial")
	public static class PlainTable<K, V> extends HashMap<K, V> {
	}

	public record HoldsContainers(NamedBag<String> namedBag, PlainBag<String> plainBag,
			NamedTable<String, Integer> namedTable, PlainTable<String, Integer> plainTable) {
	}

	@Transparent
	@Typename(name = "named_wrapper")
	public record NamedWrapper(String text) {
	}

	@Transparent
	public record PlainWrapper(String text) {
	}

	// --- not inherited

	@Typename(name = "base_code")
	public abstract static class BaseCode {
		private final String code;

		protected BaseCode(String code) {
			this.code = code;
		}

		@Atom
		public String code() {
			return code;
		}
	}

	public static final class DerivedCode extends BaseCode {
		@Atom
		public DerivedCode(String code) {
			super(code);
		}
	}

	// --- blank names

	@Typename(name = "")
	public record EmptyName(String text) {
	}

	@Typename(name = " \t")
	public static final class BlankToken {
	}

	@Typename(name = " ")
	public interface BlankSlot {
	}

	public record Plug(int size) implements BlankSlot {
	}

	public record HoldsBlankSlot(@Union({ Plug.class }) BlankSlot slot) {
	}
}
