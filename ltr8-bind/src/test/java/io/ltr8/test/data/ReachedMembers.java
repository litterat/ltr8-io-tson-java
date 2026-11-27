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

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;

import java.util.Objects;

/**
 * Members a binder reaches through the described class rather than the class declaring them: inherited from a
 * super class that is not public, hidden by a field of the same name, or reached through no public class at all.
 */
public final class ReachedMembers {

	private ReachedMembers() {
	}

	// --- read through the constructor's analysis

	static class HiddenWithAccessor {

		private final String name;

		@Record
		public HiddenWithAccessor(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	static class HiddenWithNamedAccessor {

		private final String name;

		@Record
		public HiddenWithNamedAccessor(String name) {
			this.name = name;
		}

		@Field("label")
		public String name() {
			return Objects.requireNonNull(name);
		}
	}

	static class HiddenNameBase {

		public final String name;

		public HiddenNameBase(String name) {
			this.name = name;
		}
	}

	static class OtherTypedNameBetween extends HiddenNameBase {

		@SuppressWarnings("unused")
		private final int name = 7;

		public OtherTypedNameBetween(String name) {
			super(name);
		}
	}

	/** The name of its bound field reaches, from the class between, a field of that name and another type. */
	public static class InheritsOtherTypedShadow extends OtherTypedNameBetween {

		@Record
		public InheritsOtherTypedShadow(String name) {
			super(name);
		}
	}

	/** Its private field of the same name and type hides the public field of its super class that is not public. */
	public static class HidesHiddenFinalField extends HiddenNameBase {

		@SuppressWarnings("unused")
		private final String name = "own";

		@Record
		public HidesHiddenFinalField(String name) {
			super(name);
		}
	}

	/** Its private field of the same name and another type stands beside the public field it inherits. */
	public static class HidesHiddenFinalFieldByType extends HiddenNameBase {

		@SuppressWarnings("unused")
		private final int name = 7;

		@Record
		public HidesHiddenFinalFieldByType(String name) {
			super(name);
		}
	}

	/** Neither it nor the super class declaring its bound field is public. */
	static class HiddenInheritsHiddenField extends HiddenNameBase {

		@Record
		public HiddenInheritsHiddenField(String name) {
			super(name);
		}
	}

	public static class PublicNameBase {

		public final String name;

		public PublicNameBase(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((PublicNameBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** Its own private field hides the public final field of its public super class. */
	public static class HidesPublicFinalField extends PublicNameBase {

		@SuppressWarnings("unused")
		private final String name = "own";

		@Record
		public HidesPublicFinalField(String name) {
			super(name);
		}
	}

	// --- read and written as a public field or a pair

	static class HiddenSetterBase {

		protected String value;

		public void setValue(String value) {
			this.value = value;
		}
	}

	/** Its getter pairs with the setter a super class that is not public declares. */
	public static class ReadsHiddenSetter extends HiddenSetterBase {

		@Record
		public ReadsHiddenSetter() {
		}

		public String getValue() {
			return value;
		}
	}

	static class HiddenMutableNameBase {

		public String name;
	}

	/** Its private field of the same name and type hides the public field of its super class that is not public. */
	public static class HidesHiddenMutableField extends HiddenMutableNameBase {

		@SuppressWarnings("unused")
		private String name = "own";

		@Record
		public HidesHiddenMutableField() {
		}
	}

	/** Its private field of the same name and another type stands beside the public field it inherits. */
	public static class HidesHiddenMutableFieldByType extends HiddenMutableNameBase {

		@SuppressWarnings("unused")
		private int name = 7;

		@Record
		public HidesHiddenMutableFieldByType() {
		}
	}

	static class OtherTypedMutableNameBetween extends HiddenMutableNameBase {

		@SuppressWarnings("unused")
		private int name = 7;
	}

	/** The name of its public field reaches, from the class between, a field of that name and another type. */
	public static class InheritsOtherTypedMutableShadow extends OtherTypedMutableNameBetween {

		@Record
		public InheritsOtherTypedMutableShadow() {
		}
	}

	static class HiddenPairBase {

		private String value;

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}
	}

	/** Neither it nor the super class declaring its pair is public. */
	static class HiddenInheritsHiddenPair extends HiddenPairBase {

		@Record
		public HiddenInheritsHiddenPair() {
		}
	}

	public static class PublicMutableNameBase {

		public String name;
	}

	/** Its private field of the same name and type hides the public field of its public super class. */
	public static class HidesPublicMutableField extends PublicMutableNameBase {

		@SuppressWarnings("unused")
		private String name = "own";

		@Record
		public HidesPublicMutableField() {
		}
	}
}
