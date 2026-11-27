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
import io.ltr8.annotation.Union;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

	// --- @Union on a constructor parameter, its field and its accessor: each that is present states one union

	public static class UnionOnBoth {

		private final Object tag;

		@Record
		public UnionOnBoth(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}

		@Union({ String.class, Integer.class })
		public Object tag() {
			return tag;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnionOnBoth other && Objects.equals(tag, other.tag);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(tag);
		}
	}

	public static class UnionMembersDiffer {

		private final Object tag;

		@Record
		public UnionMembersDiffer(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}

		@Union({ String.class })
		public Object tag() {
			return tag;
		}
	}

	public static class UnionSealingDiffers {

		private final Object tag;

		@Record
		public UnionSealingDiffers(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}

		@Union(value = { String.class, Integer.class }, sealed = false)
		public Object tag() {
			return tag;
		}
	}

	public static class UnionOnParameter {

		private final Object tag;

		@Record
		public UnionOnParameter(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}

		public Object tag() {
			return tag;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnionOnParameter other && Objects.equals(tag, other.tag);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(tag);
		}
	}

	public static class UnionOnAccessor {

		private final Object tag;

		@Record
		public UnionOnAccessor(Object tag) {
			this.tag = tag;
		}

		@Union({ String.class, Integer.class })
		public Object tag() {
			return tag;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnionOnAccessor other && Objects.equals(tag, other.tag);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(tag);
		}
	}

	/** Its accessor does more than return the field, so it is matched by name. */
	public static class UnionOnComputingAccessor {

		private final Object tag;

		@Record
		public UnionOnComputingAccessor(Object tag) {
			this.tag = tag;
		}

		@Union({ String.class, Integer.class })
		public Object tag() {
			return Objects.requireNonNull(tag);
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnionOnComputingAccessor other && Objects.equals(tag, other.tag);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(tag);
		}
	}

	public static class UnionOnFieldAndParameter {

		@Union({ String.class, Integer.class })
		private final Object tag;

		@Record
		public UnionOnFieldAndParameter(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}

		public Object tag() {
			return tag;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof UnionOnFieldAndParameter other && Objects.equals(tag, other.tag);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(tag);
		}
	}

	public static class UnionOnFieldDiffers {

		@Union({ String.class })
		private final Object tag;

		@Record
		public UnionOnFieldDiffers(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}

		public Object tag() {
			return tag;
		}
	}

	/** Names no member on either, so the two agree and the union is refused for its own sake. */
	public static class EmptyUnionOnBoth {

		private final Object tag;

		@Record
		public EmptyUnionOnBoth(@Union Object tag) {
			this.tag = tag;
		}

		@Union
		public Object tag() {
			return tag;
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

	// --- the member that reads a constructor-bound field back: a public instance method, else a public field

	public static class PackagePrivateAccessor {

		private final String name;

		@Record
		public PackagePrivateAccessor(String name) {
			this.name = name;
		}

		String name() {
			return name;
		}
	}

	public static class ProtectedAccessor {

		private final String name;

		@Record
		public ProtectedAccessor(String name) {
			this.name = name;
		}

		protected String name() {
			return name;
		}
	}

	public static class StaticMethodNamedForField {

		@SuppressWarnings("unused")
		private final String name;

		@Record
		public StaticMethodNamedForField(String name) {
			this.name = name;
		}

		public static String name() {
			return "static";
		}
	}

	public static class PrivateAccessorOverPublicField {

		public final String name;

		@Record
		public PrivateAccessorOverPublicField(String name) {
			this.name = name;
		}

		@SuppressWarnings("unused")
		private String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PrivateAccessorOverPublicField other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class PackagePrivateAccessorOverPublicField {

		public final String name;

		@Record
		public PackagePrivateAccessorOverPublicField(String name) {
			this.name = name;
		}

		String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PackagePrivateAccessorOverPublicField other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class ProtectedAccessorOverPublicField {

		public final String name;

		@Record
		public ProtectedAccessorOverPublicField(String name) {
			this.name = name;
		}

		protected String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof ProtectedAccessorOverPublicField other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class StaticMethodOverPublicField {

		public final String name;

		@Record
		public StaticMethodOverPublicField(String name) {
			this.name = name;
		}

		public static String name() {
			return "static";
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof StaticMethodOverPublicField other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** Its private method returning the field comes before its public accessor in the class file. */
	public static class PrivateAccessorBeforePublicAccessor {

		private final String name;

		@Record
		public PrivateAccessorBeforePublicAccessor(String name) {
			this.name = name;
		}

		@SuppressWarnings("unused")
		private String secretName() {
			return name;
		}

		public String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PrivateAccessorBeforePublicAccessor other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class PublicFinalReference {

		public final String name;

		@Record
		public PublicFinalReference(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PublicFinalReference other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** Its {@code long} and {@code double} parameters each take two local slots. */
	public static class PublicFinalPrimitives {

		public final int count;

		public final long size;

		public final double ratio;

		public final boolean flag;

		@Record
		public PublicFinalPrimitives(int count, long size, double ratio, boolean flag) {
			this.count = count;
			this.size = size;
			this.ratio = ratio;
			this.flag = flag;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PublicFinalPrimitives other && count == other.count && size == other.size
					&& ratio == other.ratio && flag == other.flag;
		}

		@Override
		public int hashCode() {
			return Objects.hash(count, size, ratio, flag);
		}
	}

	public static class PublicFinalList {

		public final List<String> items;

		@Record
		public PublicFinalList(List<String> items) {
			this.items = items;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PublicFinalList other && Objects.equals(items, other.items);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(items);
		}
	}

	public static class PublicFinalOptional {

		public final Optional<String> note;

		@Record
		public PublicFinalOptional(Optional<String> note) {
			this.note = note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PublicFinalOptional other && Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(note);
		}
	}

	public static class AccessorBesidePublicFinalField {

		public final String name;

		@Record
		public AccessorBesidePublicFinalField(String name) {
			this.name = name;
		}

		@Field("label")
		public String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof AccessorBesidePublicFinalField other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class NamedPublicFinalField {

		@Field("label")
		public final String name;

		@Record
		public NamedPublicFinalField(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof NamedPublicFinalField other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** Its constructor stores a computed value, so {@code @Field} names the parameter. */
	public static class NamedParameterOverPublicField {

		public final String name;

		@Record
		public NamedParameterOverPublicField(@Field("name") String name) {
			this.name = name.strip();
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof NamedParameterOverPublicField other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	// --- a public final field a super class declares, bound through super(…)

	public static class PublicFieldBase {

		public final String name;

		public PublicFieldBase(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((PublicFieldBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class InheritsPublicField extends PublicFieldBase {

		private final int count;

		@Record
		public InheritsPublicField(String name, int count) {
			super(name);
			this.count = count;
		}

		public int count() {
			return count;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && count == ((InheritsPublicField) obj).count;
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + count;
		}
	}

	public static class GenericPublicFieldBase<T> {

		public final T value;

		public GenericPublicFieldBase(T value) {
			this.value = value;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass()
					&& Objects.equals(value, ((GenericPublicFieldBase<?>) obj).value);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(value);
		}
	}

	/** Its inherited field holds a {@code String} as the erased {@code Object}. */
	public static class InheritsGenericPublicField extends GenericPublicFieldBase<String> {

		@Record
		public InheritsGenericPublicField(String value) {
			super(value);
		}
	}

	public static class PublicFieldAccessorBase {

		public final String value;

		public PublicFieldAccessorBase(String value) {
			this.value = value;
		}

		public String value() {
			return value;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass()
					&& Objects.equals(value, ((PublicFieldAccessorBase) obj).value);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(value);
		}
	}

	/** Its override returns its own field, so the super class's accessor would not read the super class's field. */
	public static class OverridesPublicFieldAccessor extends PublicFieldAccessorBase {

		private final String value;

		@Record
		public OverridesPublicFieldAccessor(String baseValue, String value) {
			super(baseValue);
			this.value = value;
		}

		@Override
		@Field("own")
		public String value() {
			return value;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && Objects.equals(value, ((OverridesPublicFieldAccessor) obj).value);
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Objects.hashCode(value);
		}
	}

	// --- a member of a super class that is not public: the public lookup reaches it through a public subclass

	static class HiddenFieldBase {

		public final String name;

		public HiddenFieldBase(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((HiddenFieldBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class InheritsHiddenField extends HiddenFieldBase {

		@Record
		public InheritsHiddenField(String name) {
			super(name);
		}
	}

	static class HiddenAccessorBase {

		private final String name;

		public HiddenAccessorBase(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((HiddenAccessorBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class InheritsHiddenAccessor extends HiddenAccessorBase {

		@Record
		public InheritsHiddenAccessor(String name) {
			super(name);
		}
	}

	/** Its accessor does more than return the field, so it is matched by its name and {@code @Field}. */
	static class HiddenNamedAccessorBase {

		private final String name;

		public HiddenNamedAccessorBase(String name) {
			this.name = name;
		}

		@Field("label")
		public String name() {
			return Objects.requireNonNull(name);
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass()
					&& Objects.equals(name, ((HiddenNamedAccessorBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class InheritsHiddenNamedAccessor extends HiddenNamedAccessorBase {

		@Record
		public InheritsHiddenNamedAccessor(String name) {
			super(name);
		}
	}

	static class HiddenGrandBase {

		public final String name;

		private final int count;

		public HiddenGrandBase(String name, int count) {
			this.name = name;
			this.count = count;
		}

		public int count() {
			return count;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((HiddenGrandBase) obj).name)
					&& count == ((HiddenGrandBase) obj).count;
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count);
		}
	}

	static class HiddenParent extends HiddenGrandBase {

		public HiddenParent(String name, int count) {
			super(name, count);
		}
	}

	/** Its members sit two levels up, above another super class that is not public. */
	public static class InheritsHiddenTwoUp extends HiddenParent {

		@Record
		public InheritsHiddenTwoUp(String name, int count) {
			super(name, count);
		}
	}

	public static class PublicTop {

		public final String name;

		private final int count;

		public PublicTop(String name, int count) {
			this.name = name;
			this.count = count;
		}

		public int count() {
			return count;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((PublicTop) obj).name)
					&& count == ((PublicTop) obj).count;
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count);
		}
	}

	static class HiddenBetween extends PublicTop {

		public final String label;

		private final int size;

		public HiddenBetween(String name, int count, String label, int size) {
			super(name, count);
			this.label = label;
			this.size = size;
		}

		public int size() {
			return size;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && Objects.equals(label, ((HiddenBetween) obj).label)
					&& size == ((HiddenBetween) obj).size;
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Objects.hash(label, size);
		}
	}

	/** Inherits members of a public class and of the super class that is not public between the two. */
	public static class InheritsAcrossHidden extends HiddenBetween {

		@Record
		public InheritsAcrossHidden(String name, int count, String label, int size) {
			super(name, count, label, size);
		}
	}

	static class HiddenShadowedBase {

		public final String name;

		public HiddenShadowedBase(String name) {
			this.name = name;
		}
	}

	static class HiddenShadowing extends HiddenShadowedBase {

		@SuppressWarnings("unused")
		private final String name = "hidden";

		public HiddenShadowing(String name) {
			super(name);
		}
	}

	/** Through it the name of its bound field resolves to another field, which a super class declares private. */
	public static class InheritsShadowedHiddenField extends HiddenShadowing {

		@Record
		public InheritsShadowedHiddenField(String name) {
			super(name);
		}
	}

	static class HiddenDescribed {

		public final String name;

		@Record
		public HiddenDescribed(String name) {
			this.name = name;
		}
	}

	static class HiddenInheritsPublicField extends PublicFieldBase {

		@Record
		public HiddenInheritsPublicField(String name) {
			super(name);
		}
	}

	// --- a public field that is not final, bound by the constructor and read and written through the field

	public static class PublicMutableReference {

		public String name;

		@Record
		public PublicMutableReference(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PublicMutableReference other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class PublicMutablePrimitives {

		public int count;

		public long size;

		public double ratio;

		public boolean flag;

		@Record
		public PublicMutablePrimitives(int count, long size, double ratio, boolean flag) {
			this.count = count;
			this.size = size;
			this.ratio = ratio;
			this.flag = flag;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PublicMutablePrimitives other && count == other.count && size == other.size
					&& ratio == other.ratio && flag == other.flag;
		}

		@Override
		public int hashCode() {
			return Objects.hash(count, size, ratio, flag);
		}
	}

	public static class PublicMutableList {

		public List<String> items;

		@Record
		public PublicMutableList(List<String> items) {
			this.items = items;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PublicMutableList other && Objects.equals(items, other.items);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(items);
		}
	}

	public static class PublicMutableOptional {

		public Optional<String> note;

		@Record
		public PublicMutableOptional(Optional<String> note) {
			this.note = note;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PublicMutableOptional other && Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(note);
		}
	}

	public static class NamedPublicMutableField {

		@Field("label")
		public String name;

		@Record
		public NamedPublicMutableField(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof NamedPublicMutableField other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class PublicMutableFieldBase {

		public String name;

		public PublicMutableFieldBase(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass()
					&& Objects.equals(name, ((PublicMutableFieldBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class InheritsPublicMutableField extends PublicMutableFieldBase {

		private final int count;

		@Record
		public InheritsPublicMutableField(String name, int count) {
			super(name);
			this.count = count;
		}

		public int count() {
			return count;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && count == ((InheritsPublicMutableField) obj).count;
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + count;
		}
	}

	/** Its constructor binds one public field that is not final and leaves the other to be set after it. */
	public static class BindsOnePublicMutableField {

		public String name;

		public String note;

		@Record
		public BindsOnePublicMutableField(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof BindsOnePublicMutableField other && Objects.equals(name, other.name)
					&& Objects.equals(note, other.note);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, note);
		}
	}

	// --- a field a super class declares, which the subclass's own constructor stores

	public static class ProtectedNameBase {

		protected String name;

		public String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((ProtectedNameBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** Read back through its super class's accessor. */
	public static class StoresInheritedField extends ProtectedNameBase {

		@Record
		public StoresInheritedField(String name) {
			this.name = name;
		}
	}

	public static class ProtectedNameMiddle extends ProtectedNameBase {
	}

	public static class StoresInheritedFieldTwoUp extends ProtectedNameMiddle {

		@Record
		public StoresInheritedFieldTwoUp(String name) {
			this.name = name;
		}
	}

	public static class ProtectedFieldBase {

		protected String name;

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((ProtectedFieldBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** Its own accessor returns the field its super class declares. */
	public static class StoresInheritedFieldReadBySubclass extends ProtectedFieldBase {

		@Record
		public StoresInheritedFieldReadBySubclass(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class UnsetPublicFieldBase {

		public String name;

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass()
					&& Objects.equals(name, ((UnsetPublicFieldBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** No accessor: the inherited field is read directly. */
	public static class StoresInheritedPublicField extends UnsetPublicFieldBase {

		@Record
		public StoresInheritedPublicField(String name) {
			this.name = name;
		}
	}

	static class HiddenProtectedNameBase {

		protected String name;

		public String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass()
					&& Objects.equals(name, ((HiddenProtectedNameBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class StoresHiddenInheritedField extends HiddenProtectedNameBase {

		@Record
		public StoresHiddenInheritedField(String name) {
			this.name = name;
		}
	}

	static class HiddenPublicMutableFieldBase {

		public String name;

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass()
					&& Objects.equals(name, ((HiddenPublicMutableFieldBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class StoresHiddenInheritedPublicField extends HiddenPublicMutableFieldBase {

		@Record
		public StoresHiddenInheritedPublicField(String name) {
			this.name = name;
		}
	}

	public static class AnnotatedFieldBase {

		@Field("label")
		protected String name;

		@Union({ String.class, Integer.class })
		protected Object tag;

		public String name() {
			return name;
		}

		public Object tag() {
			return tag;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass()
					&& Objects.equals(name, ((AnnotatedFieldBase) obj).name)
					&& Objects.equals(tag, ((AnnotatedFieldBase) obj).tag);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, tag);
		}
	}

	public static class StoresAnnotatedInheritedFields extends AnnotatedFieldBase {

		@Record
		public StoresAnnotatedInheritedFields(String name, Object tag) {
			this.name = name;
			this.tag = tag;
		}
	}

	public static class HidingBase {

		protected String name;

		public String baseName() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((HidingBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** Stores its argument in the super class's field that its own field of that name hides. */
	public static class StoresHiddenSuperField extends HidingBase {

		@SuppressWarnings("unused")
		private String name = "own";

		@Record
		public StoresHiddenSuperField(String name) {
			super.name = name;
		}
	}

	/** Stores one argument in the super class's field and one in its own field hiding it. */
	public static class StoresBothFieldsOfOneName extends HidingBase {

		private String name;

		@Record
		public StoresBothFieldsOfOneName(String base, String own) {
			super.name = base;
			this.name = own;
		}

		@Field("own")
		public String ownName() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && Objects.equals(name, ((StoresBothFieldsOfOneName) obj).name);
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Objects.hashCode(name);
		}
	}
}
