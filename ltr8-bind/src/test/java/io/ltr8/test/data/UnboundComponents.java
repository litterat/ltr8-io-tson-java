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

import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Unbound;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Classes holding a component the class owns, beside one the wire fills.
 */
public final class UnboundComponents {

	private UnboundComponents() {
	}

	public record UnboundBoolean(String name, @Unbound boolean cache) {
	}

	public record UnboundByte(String name, @Unbound byte cache) {
	}

	public record UnboundChar(String name, @Unbound char cache) {
	}

	public record UnboundShort(String name, @Unbound short cache) {
	}

	public record UnboundInt(String name, @Unbound int cache) {
	}

	public record UnboundLong(String name, @Unbound long cache) {
	}

	public record UnboundFloat(String name, @Unbound float cache) {
	}

	public record UnboundDouble(String name, @Unbound double cache) {
	}

	public record UnboundText(String name, @Unbound String cache) {
	}

	public record UnboundOptional(String name, @Unbound Optional<String> cache) {
	}

	public record UnboundOptionalInt(String name, @Unbound OptionalInt cache) {
	}

	/** The primitive the class owns comes first, so the bound component's place in the values differs from its own. */
	public record UnboundBeforeBound(@Unbound long cache, int count) {
	}

	/** Marked on the parameters of an explicit canonical constructor and not in the record header. */
	public record UnboundCanonicalParameters(String name, int count, String note) {

		public UnboundCanonicalParameters(String name, @Unbound int count, @Unbound String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}
	}

	/** Marked on the parameters of the constructor profile {@code p} selects. */
	public record UnboundProfileParameters(String name, int count, String note, String tier) {

		@Profile(value = "p", fields = { "name", "count", "note" })
		public UnboundProfileParameters(String name, @Unbound int count, @Unbound String note) {
			this(name, count, note, "t");
		}
	}

	/** Marked in the record header, on the components the constructor profile {@code p} selects takes. */
	public record UnboundProfileComponents(String name, @Unbound int count, @Unbound String note, String tier) {

		@Profile(value = "p", fields = { "name", "count", "note" })
		public UnboundProfileComponents(String name, int count, String note) {
			this(name, count, note, "t");
		}
	}

	/** A hand-written class marking its constructor's parameters. */
	public static final class UnboundConstructorParameters {

		private final String name;

		private final int count;

		private final String note;

		@Record
		public UnboundConstructorParameters(String name, @Unbound int count, @Unbound String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}

		public String name() {
			return name;
		}

		public int count() {
			return count;
		}

		public String note() {
			return note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnboundConstructorParameters other && Objects.equals(name, other.name)
					&& count == other.count && Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count, note);
		}

		@Override
		public String toString() {
			return name + "," + count + "," + note;
		}
	}

	/** A hand-written class marking the fields its constructor stores in. */
	public static final class UnboundConstructorFields {

		private final String name;

		@Unbound
		private final int count;

		@Unbound
		private final String note;

		@Record
		public UnboundConstructorFields(String name, int count, String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}

		public String name() {
			return name;
		}

		public int count() {
			return count;
		}

		public String note() {
			return note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnboundConstructorFields other && Objects.equals(name, other.name)
					&& count == other.count && Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count, note);
		}

		@Override
		public String toString() {
			return name + "," + count + "," + note;
		}
	}

	/** A hand-written class marking the accessors of its constructor's components. */
	public static final class UnboundConstructorAccessors {

		private final String name;

		private final int count;

		private final String note;

		@Record
		public UnboundConstructorAccessors(String name, int count, String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}

		public String name() {
			return name;
		}

		@Unbound
		public int count() {
			return count;
		}

		@Unbound
		public String note() {
			return note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnboundConstructorAccessors other && Objects.equals(name, other.name)
					&& count == other.count && Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count, note);
		}

		@Override
		public String toString() {
			return name + "," + count + "," + note;
		}
	}

	/** A hand-written class marking the setters of its constructor's components. */
	public static final class UnboundConstructorSetters {

		private final String name;

		private int count;

		private String note;

		@Record
		public UnboundConstructorSetters(String name, int count, String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}

		public String name() {
			return name;
		}

		public int getCount() {
			return count;
		}

		@Unbound
		public void setCount(int count) {
			this.count = count;
		}

		public String getNote() {
			return note;
		}

		@Unbound
		public void setNote(String note) {
			this.note = note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnboundConstructorSetters other && Objects.equals(name, other.name)
					&& count == other.count && Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count, note);
		}

		@Override
		public String toString() {
			return name + "," + count + "," + note;
		}
	}

	/** A class of public fields, two of them marked. */
	public static final class UnboundPublicFields {

		public String name;

		@Unbound
		public int count;

		@Unbound
		public String note;

		@Record
		public UnboundPublicFields() {
		}

		public UnboundPublicFields(String name, int count, String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnboundPublicFields other && Objects.equals(name, other.name)
					&& count == other.count && Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count, note);
		}

		@Override
		public String toString() {
			return name + "," + count + "," + note;
		}
	}

	/** A class of getter/setter pairs, two of them marked on the getter. */
	public static final class UnboundGetters {

		private String name;

		private int count;

		private String note;

		@Record
		public UnboundGetters() {
		}

		public UnboundGetters(String name, int count, String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		@Unbound
		public int getCount() {
			return count;
		}

		public void setCount(int count) {
			this.count = count;
		}

		@Unbound
		public String getNote() {
			return note;
		}

		public void setNote(String note) {
			this.note = note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnboundGetters other && Objects.equals(name, other.name) && count == other.count
					&& Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count, note);
		}

		@Override
		public String toString() {
			return name + "," + count + "," + note;
		}
	}

	/** A class of getter/setter pairs, two of them marked on the setter. */
	public static final class UnboundSetters {

		private String name;

		private int count;

		private String note;

		@Record
		public UnboundSetters() {
		}

		public UnboundSetters(String name, int count, String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public int getCount() {
			return count;
		}

		@Unbound
		public void setCount(int count) {
			this.count = count;
		}

		public String getNote() {
			return note;
		}

		@Unbound
		public void setNote(String note) {
			this.note = note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnboundSetters other && Objects.equals(name, other.name) && count == other.count
					&& Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count, note);
		}

		@Override
		public String toString() {
			return name + "," + count + "," + note;
		}
	}

	/** A class of getter/setter pairs, two of them marked on the private field each reads and writes. */
	public static final class UnboundPairFields {

		private String name;

		@Unbound
		private int count;

		@Unbound
		private String note;

		@Record
		public UnboundPairFields() {
		}

		public UnboundPairFields(String name, int count, String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public int getCount() {
			return count;
		}

		public void setCount(int count) {
			this.count = count;
		}

		public String getNote() {
			return note;
		}

		public void setNote(String note) {
			this.note = note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnboundPairFields other && Objects.equals(name, other.name) && count == other.count
					&& Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count, note);
		}

		@Override
		public String toString() {
			return name + "," + count + "," + note;
		}
	}

	/** A marked public field and a marked pair, each starting from a value of the class's own. */
	public static final class UnboundSettableInitialised {

		public String name;

		@Unbound
		public int count = 5;

		private String note = "kept";

		@Record
		public UnboundSettableInitialised() {
		}

		public UnboundSettableInitialised(String name, int count, String note) {
			this.name = name;
			this.count = count;
			this.note = note;
		}

		@Unbound
		public String getNote() {
			return note;
		}

		public void setNote(String note) {
			this.note = note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnboundSettableInitialised other && Objects.equals(name, other.name)
					&& count == other.count && Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count, note);
		}

		@Override
		public String toString() {
			return name + "," + count + "," + note;
		}
	}
}
