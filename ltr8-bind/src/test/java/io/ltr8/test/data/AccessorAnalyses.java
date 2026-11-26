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
 * Hand-written immutable classes whose accessors the analysis must pair with the right field.
 */
public final class AccessorAnalyses {

	private AccessorAnalyses() {
	}

	// --- a super class and a subclass each declaring a private field of one name

	public static class ValueBase {

		private final String value;

		public ValueBase(String value) {
			this.value = value;
		}

		public String baseValue() {
			return value;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(value, ((ValueBase) obj).value);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(value);
		}
	}

	/** Its own field is stored after the super class's. */
	public static class ShadowsAfterSuper extends ValueBase {

		private final String value;

		@Record
		public ShadowsAfterSuper(String baseValue, String value) {
			super(baseValue);
			this.value = value;
		}

		@Field("own")
		public String ownValue() {
			return value;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && Objects.equals(value, ((ShadowsAfterSuper) obj).value);
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Objects.hashCode(value);
		}
	}

	/** Its own field is stored before the super class's. */
	public static class ShadowsBeforeSuper extends ValueBase {

		private final String value;

		@Record
		public ShadowsBeforeSuper(String baseValue, String value) {
			this.value = value;
			super(baseValue);
		}

		@Field("own")
		public String ownValue() {
			return value;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && Objects.equals(value, ((ShadowsBeforeSuper) obj).value);
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Objects.hashCode(value);
		}
	}

	/** Its super class names the inherited component through {@code @Field} on its accessor. */
	public static class RenamedBase {

		private final String value;

		public RenamedBase(String value) {
			this.value = value;
		}

		@Field("base")
		public String baseValue() {
			return value;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(value, ((RenamedBase) obj).value);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(value);
		}
	}

	public static class ShadowsRenamedBase extends RenamedBase {

		private final String value;

		@Record
		public ShadowsRenamedBase(String baseValue, String value) {
			super(baseValue);
			this.value = value;
		}

		public String ownValue() {
			return value;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && Objects.equals(value, ((ShadowsRenamedBase) obj).value);
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Objects.hashCode(value);
		}
	}

	/** Names its own field apart on the field, where the others name it on the accessor. */
	public static class ShadowsNamedOnField extends ValueBase {

		@Field("own")
		private final String value;

		@Record
		public ShadowsNamedOnField(String baseValue, String value) {
			super(baseValue);
			this.value = value;
		}

		public String ownValue() {
			return value;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && Objects.equals(value, ((ShadowsNamedOnField) obj).value);
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Objects.hashCode(value);
		}
	}

	/** Leaves its two fields of one name unnamed apart. */
	public static class ShadowsUnnamed extends ValueBase {

		private final String value;

		@Record
		public ShadowsUnnamed(String baseValue, String value) {
			super(baseValue);
			this.value = value;
		}

		public String ownValue() {
			return value;
		}
	}

	// --- a subclass overriding its super class's accessor: a handle on the super class's dispatches to the override

	public static class OverridableBase {

		private final String value;

		public OverridableBase(String value) {
			this.value = value;
		}

		public String value() {
			return value;
		}
	}

	/** Its override returns its own field of the super class field's name. */
	public static class OverridesShadowing extends OverridableBase {

		private final String value;

		@Record
		public OverridesShadowing(String baseValue, String value) {
			super(baseValue);
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}
	}

	public static class RenamedOverridableBase {

		private final String value;

		public RenamedOverridableBase(String value) {
			this.value = value;
		}

		@Field("base")
		public String value() {
			return value;
		}
	}

	/** As {@link OverridesShadowing}, with the super class accessor naming its component apart. */
	public static class OverridesRenamedShadowing extends RenamedOverridableBase {

		private final String value;

		@Record
		public OverridesRenamedShadowing(String baseValue, String value) {
			super(baseValue);
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}
	}

	/** Its override returns what the super class's accessor does. */
	public static class OverridesThroughSuper extends OverridableBase {

		@Record
		public OverridesThroughSuper(String value) {
			super(value);
		}

		@Override
		public String value() {
			return super.value();
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof OverridesThroughSuper other && Objects.equals(value(), other.value());
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(value());
		}
	}

	// --- an accessor reading a field of the same name on another object

	public static class Inner {

		public final String name;

		public Inner(String name) {
			this.name = name;
		}
	}

	/** Its second accessor, after the first in the class file, returns {@code inner.name}, not its own field. */
	public static class Wraps {

		private final String name;

		private final Inner inner;

		@Record
		public Wraps(String name) {
			this.name = name;
			this.inner = new Inner(name + "!");
		}

		public String name() {
			return name;
		}

		public String innerName() {
			return inner.name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof Wraps other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** Its static method, after its accessor in the class file, returns the field of another instance. */
	public static class ReadsStatically {

		public static final ReadsStatically DEFAULT = new ReadsStatically("default");

		private final String name;

		@Record
		public ReadsStatically(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}

		public static String defaultName() {
			ReadsStatically fallback = DEFAULT;
			return fallback.name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof ReadsStatically other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	// --- a subclass accessor reading a field its super class declares

	public static class LabelBase {

		protected final String label;

		public LabelBase(String label) {
			this.label = label;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(label, ((LabelBase) obj).label);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(label);
		}
	}

	public static class Labelled extends LabelBase {

		@Record
		public Labelled(String label) {
			super(label);
		}

		public String label() {
			return label;
		}
	}
}
