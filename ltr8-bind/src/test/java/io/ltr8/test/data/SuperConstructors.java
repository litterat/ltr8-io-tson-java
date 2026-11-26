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
import java.util.Optional;

/**
 * Hand-written immutable classes whose constructors call a public super class constructor.
 */
public final class SuperConstructors {

	private SuperConstructors() {
	}

	public static class Named {

		private final String name;

		public Named(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((Named) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class Counted extends Named {

		private final int count;

		@Record
		public Counted(String name, int count) {
			super(name);
			this.count = count;
		}

		public int count() {
			return count;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && count == ((Counted) obj).count;
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + count;
		}
	}

	/** Its {@code long} and {@code double} take two local slots each. */
	public static class Sized extends Named {

		private final long size;

		private final double ratio;

		@Record
		public Sized(String name, long size, double ratio) {
			super(name);
			this.size = size;
			this.ratio = ratio;
		}

		public long size() {
			return size;
		}

		public double ratio() {
			return ratio;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && size == ((Sized) obj).size && ratio == ((Sized) obj).ratio;
		}

		@Override
		public int hashCode() {
			return 31 * (31 * super.hashCode() + Long.hashCode(size)) + Double.hashCode(ratio);
		}
	}

	public record HoldsSized(String label, Sized value) {
	}

	public record HoldsCounted(Counted value, int copies) {
	}

	public static class Tagged {

		private final Optional<String> tag;

		public Tagged(Optional<String> tag) {
			this.tag = tag;
		}

		public Optional<String> tag() {
			return tag;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && tag.equals(((Tagged) obj).tag);
		}

		@Override
		public int hashCode() {
			return tag.hashCode();
		}
	}

	public static class TaggedCount extends Tagged {

		private final int count;

		@Record
		public TaggedCount(Optional<String> tag, int count) {
			super(tag);
			this.count = count;
		}

		public int count() {
			return count;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && count == ((TaggedCount) obj).count;
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + count;
		}
	}

	// --- arguments passed up out of their own order

	/** Passes its second argument up, so the super class's field is not bound by a leading one. */
	public static class OffsetSuper extends Named {

		private final String extra;

		@Record
		public OffsetSuper(String extra, String name) {
			super(name);
			this.extra = extra;
		}

		public String extra() {
			return extra;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && Objects.equals(extra, ((OffsetSuper) obj).extra);
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Objects.hashCode(extra);
		}
	}

	public static class Pair {

		private final String first;

		private final String second;

		public Pair(String first, String second) {
			this.first = first;
			this.second = second;
		}

		public String first() {
			return first;
		}

		public String second() {
			return second;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(first, ((Pair) obj).first)
					&& Objects.equals(second, ((Pair) obj).second);
		}

		@Override
		public int hashCode() {
			return 31 * Objects.hashCode(first) + Objects.hashCode(second);
		}
	}

	/** Takes its super class's two arguments of one type in the opposite order. */
	public static class SwappedPair extends Pair {

		@Record
		public SwappedPair(String second, String first) {
			super(first, second);
		}
	}

	/** Reorders arguments of different widths: each must reach the field its own type fits. */
	public static class Resized extends Sized {

		@Record
		public Resized(double ratio, long size, String name) {
			super(name, size, ratio);
		}
	}

	// --- super constructors more than one deep

	public static class Layered extends Counted {

		private final String colour;

		@Record
		public Layered(String name, int count, String colour) {
			super(name, count);
			this.colour = colour;
		}

		public String colour() {
			return colour;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && Objects.equals(colour, ((Layered) obj).colour);
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Objects.hashCode(colour);
		}
	}

	/** Three super constructors deep, each argument passed up from a different position. */
	public static class Tiered extends Layered {

		private final boolean flag;

		@Record
		public Tiered(boolean flag, String colour, int count, String name) {
			super(name, count, colour);
			this.flag = flag;
		}

		public boolean flag() {
			return flag;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && flag == ((Tiered) obj).flag;
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Boolean.hashCode(flag);
		}
	}

	// --- a super constructor taking a wider type than the argument

	public static class WideBase {

		private final CharSequence text;

		public WideBase(CharSequence text) {
			this.text = text;
		}

		public CharSequence text() {
			return text;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(text, ((WideBase) obj).text);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(text);
		}
	}

	public static class NarrowsSuper extends WideBase {

		@Record
		public NarrowsSuper(String text) {
			super(text);
		}
	}

	public static class GenericBase<T> {

		private final T value;

		public GenericBase(T value) {
			this.value = value;
		}

		public T value() {
			return value;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(value, ((GenericBase<?>) obj).value);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(value);
		}
	}

	/** Its super constructor erases to {@code (Object)}. */
	public static class TextValue extends GenericBase<String> {

		@Record
		public TextValue(String value) {
			super(value);
		}
	}

	/** Assigns its argument to a field of a wider type, read back through an accessor of that type. */
	public static class WideField {

		private final CharSequence text;

		@Record
		public WideField(String text) {
			this.text = text;
		}

		public CharSequence text() {
			return text;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof WideField other && Objects.equals(text, other.text);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(text);
		}
	}

	// --- a super constructor argument that is not one of the constructor's own parameters, unchanged: the call
	// binds nothing, and each parameter is matched, named by @Field or refused as it would be without the call

	public static class KindBase {

		private final String kind;

		public KindBase(String kind) {
			this.kind = kind;
		}

		public String kind() {
			return kind;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(kind, ((KindBase) obj).kind);
		}

		@Override
		public int hashCode() {
			return Objects.hash(kind);
		}
	}

	/** Fixes its super class's field with a constant, so that field is not a component. */
	public static class FixedKind extends KindBase {

		private final double radius;

		@Record
		public FixedKind(double radius) {
			super("circle");
			this.radius = radius;
		}

		public double radius() {
			return radius;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && radius == ((FixedKind) obj).radius;
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + Double.hashCode(radius);
		}
	}

	public static class TaggedBase {

		private final String name;

		private final String tag;

		public TaggedBase(String name, String tag) {
			this.name = name;
			this.tag = tag;
		}

		public String name() {
			return name;
		}

		public String tag() {
			return tag;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((TaggedBase) obj).name)
					&& Objects.equals(tag, ((TaggedBase) obj).tag);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, tag);
		}
	}

	public static class PassesConstant extends TaggedBase {

		@Record
		public PassesConstant(String name) {
			super(name, "fixed");
		}
	}

	public static class PassesConstantFirst extends TaggedBase {

		@Record
		public PassesConstantFirst(String tag) {
			super("fixed", tag);
		}
	}

	public static class NamedPassesConstant extends TaggedBase {

		@Record
		public NamedPassesConstant(@Field("name") String name) {
			super(name, "fixed");
		}
	}

	public static class NamedPassesConstantFirst extends TaggedBase {

		@Record
		public NamedPassesConstantFirst(@Field("tag") String tag) {
			super("fixed", tag);
		}
	}

	public static class PassesComputed extends Named {

		@Record
		public PassesComputed(String name) {
			super(name.trim());
		}
	}

	public static class NamedPassesComputed extends Named {

		@Record
		public NamedPassesComputed(@Field("name") String name) {
			super(name.trim());
		}
	}

	public static class LongBase {

		private final long size;

		public LongBase(long size) {
			this.size = size;
		}

		public long size() {
			return size;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && size == ((LongBase) obj).size;
		}

		@Override
		public int hashCode() {
			return Objects.hash(size);
		}
	}

	/** Its {@code int} argument is widened to the super constructor's {@code long}. */
	public static class PassesWidened extends LongBase {

		@Record
		public PassesWidened(int size) {
			super(size);
		}
	}

	public static class NamedPassesWidened extends LongBase {

		@Record
		public NamedPassesWidened(@Field("size") int size) {
			super(size);
		}
	}

	public static class BoxedBase {

		private final Integer size;

		public BoxedBase(Integer size) {
			this.size = size;
		}

		public Integer size() {
			return size;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(size, ((BoxedBase) obj).size);
		}

		@Override
		public int hashCode() {
			return Objects.hash(size);
		}
	}

	/** Its {@code int} argument is boxed to the super constructor's {@code Integer}. */
	public static class PassesBoxed extends BoxedBase {

		@Record
		public PassesBoxed(int size) {
			super(size);
		}
	}

	public static class NamedPassesBoxed extends BoxedBase {

		@Record
		public NamedPassesBoxed(@Field("size") int size) {
			super(size);
		}
	}

	/** Its own super constructor passes a constant further up, so stores the argument it is passed nowhere. */
	public static class ReachesConstant extends PassesConstant {

		@Record
		public ReachesConstant(String name) {
			super(name);
		}
	}
}
