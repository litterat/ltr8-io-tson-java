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
 * Hand-written immutable classes whose constructors do more than store each parameter in a field.
 */
public final class ConstructorAnalyses {

	private ConstructorAnalyses() {
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

	// --- a parameter the constructor assigns: its value is not the argument, so it binds nothing

	public static class Reassigns extends Named {

		@Record
		public Reassigns(String name) {
			name = name.trim();
			super(name);
		}
	}

	public static class ReassignsBeforeStore {

		private final String name;

		@Record
		public ReassignsBeforeStore(String name) {
			name = name.trim();
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class NamedReassignsBeforeStore {

		private final String name;

		@Record
		public NamedReassignsBeforeStore(@Field("name") String name) {
			name = name.trim();
			this.name = name;
		}

		public String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof NamedReassignsBeforeStore other && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	public static class NamedReassigns extends Named {

		@Record
		public NamedReassigns(@Field("name") String name) {
			name = name.trim();
			super(name);
		}
	}

	/** Its {@code long} parameter takes two local slots. */
	public static class ReassignsWide {

		private final long size;

		@Record
		public ReassignsWide(long size) {
			size = size * 2;
			this.size = size;
		}

		public long size() {
			return size;
		}
	}

	/** Changes its parameter by an increment, not a store. */
	public static class Increments {

		private final int count;

		@Record
		public Increments(int count) {
			count++;
			this.count = count;
		}

		public int count() {
			return count;
		}
	}

	public static class ReassignsOne {

		private final String name;

		private final int count;

		@Record
		public ReassignsOne(String name, int count) {
			name = name.trim();
			this.name = name;
			this.count = count;
		}

		public String name() {
			return name;
		}

		public int count() {
			return count;
		}
	}

	public static class NamedReassignsOne {

		private final String name;

		private final int count;

		@Record
		public NamedReassignsOne(@Field("name") String name, int count) {
			name = name.trim();
			this.name = name;
			this.count = count;
		}

		public String name() {
			return name;
		}

		public int count() {
			return count;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof NamedReassignsOne other && Objects.equals(name, other.name) && count == other.count;
		}

		@Override
		public int hashCode() {
			return 31 * Objects.hashCode(name) + count;
		}
	}

	/** Stores no field from its reassigned parameter. */
	public static class ReassignsAndDrops {

		private final int count;

		@Record
		public ReassignsAndDrops(String name, int count) {
			name = null;
			this.count = count;
		}

		public int count() {
			return count;
		}
	}

	/** Stores its parameter unchanged, then assigns the parameter. */
	public static class ReassignsAfterStore {

		private final String name;

		@Record
		public ReassignsAfterStore(String name) {
			this.name = name;
			name = null;
		}

		public String name() {
			return name;
		}
	}

	public static class TrimmingBase {

		private final String name;

		public TrimmingBase(String name) {
			name = name.trim();
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	/** Passes its parameter unchanged to a super constructor that assigns it. */
	public static class PassesToTrimming extends TrimmingBase {

		@Record
		public PassesToTrimming(String name) {
			super(name);
		}
	}

	// --- a constructor that delegates to another of its class with this(…)

	public static class Delegates {

		private final String a;

		private final Integer b;

		@Record
		public Delegates(String a, Integer b) {
			this(b, a);
		}

		private Delegates(Integer b, String a) {
			this.a = a;
			this.b = b;
		}

		public String a() {
			return a;
		}

		public Integer b() {
			return b;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof Delegates other && Objects.equals(a, other.a) && Objects.equals(b, other.b);
		}

		@Override
		public int hashCode() {
			return Objects.hash(a, b);
		}
	}

	/** Reorders arguments of different widths into a public delegate. */
	public static class DelegatesWide {

		private final String name;

		private final long size;

		private final double ratio;

		@Record
		public DelegatesWide(double ratio, long size, String name) {
			this(name, size, ratio);
		}

		public DelegatesWide(String name, long size, double ratio) {
			this.name = name;
			this.size = size;
			this.ratio = ratio;
		}

		public String name() {
			return name;
		}

		public long size() {
			return size;
		}

		public double ratio() {
			return ratio;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof DelegatesWide other && Objects.equals(name, other.name) && size == other.size
					&& ratio == other.ratio;
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, size, ratio);
		}
	}

	/** Its delegate stores its own field and passes the other up to the super constructor. */
	public static class DelegatesToSuper extends Named {

		private final int count;

		@Record
		public DelegatesToSuper(int count, String name) {
			this(name, count);
		}

		private DelegatesToSuper(String name, int count) {
			super(name);
			this.count = count;
		}

		public int count() {
			return count;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && count == ((DelegatesToSuper) obj).count;
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + count;
		}
	}

	/** Two delegates deep, the last storing a wider type than the parameter. */
	public static class DelegatesTwice {

		private final CharSequence a;

		private final Integer b;

		@Record
		public DelegatesTwice(String a, Integer b) {
			this(b, a);
		}

		private DelegatesTwice(Integer b, String a) {
			this(b, (CharSequence) a);
		}

		private DelegatesTwice(Integer b, CharSequence a) {
			this.a = a;
			this.b = b;
		}

		public CharSequence a() {
			return a;
		}

		public Integer b() {
			return b;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof DelegatesTwice other && Objects.equals(a, other.a) && Objects.equals(b, other.b);
		}

		@Override
		public int hashCode() {
			return Objects.hash(a, b);
		}
	}

	public static class DelegatesConstant {

		private final String name;

		private final int count;

		@Record
		public DelegatesConstant(String name) {
			this(name, 0);
		}

		private DelegatesConstant(String name, int count) {
			this.name = name;
			this.count = count;
		}

		public String name() {
			return name;
		}

		public int count() {
			return count;
		}
	}

	public static class NamedDelegatesConstant {

		private final String name;

		private final int count;

		@Record
		public NamedDelegatesConstant(@Field("name") String name) {
			this(name, 0);
		}

		private NamedDelegatesConstant(String name, int count) {
			this.name = name;
			this.count = count;
		}

		public String name() {
			return name;
		}

		public int count() {
			return count;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof NamedDelegatesConstant other && Objects.equals(name, other.name)
					&& count == other.count;
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, count);
		}
	}

	/** Its delegate stores the argument it is passed only after changing it. */
	public static class DelegatesToTransforming {

		private final String name;

		@Record
		public DelegatesToTransforming(String name) {
			this((CharSequence) name);
		}

		private DelegatesToTransforming(CharSequence name) {
			this.name = name.toString().trim();
		}

		public String name() {
			return name;
		}
	}

	// --- a call passing one parameter as two arguments, so another parameter is never bound

	public static class PairBase {

		private final String first;

		private final String second;

		public PairBase(String first, String second) {
			this.first = first;
			this.second = second;
		}

		public String first() {
			return first;
		}

		public String second() {
			return second;
		}
	}

	public static class SuperRepeats extends PairBase {

		@Record
		public SuperRepeats(String first, String ignored) {
			super(first, first);
		}
	}

	public static class DelegateRepeats {

		private final CharSequence first;

		private final CharSequence second;

		@Record
		public DelegateRepeats(String first, String ignored) {
			this(first, (CharSequence) first);
		}

		private DelegateRepeats(CharSequence first, CharSequence second) {
			this.first = first;
			this.second = second;
		}

		public CharSequence first() {
			return first;
		}

		public CharSequence second() {
			return second;
		}
	}

	/** Stores one parameter in two fields and the other in none. */
	public static class StoresRepeated {

		private final String first;

		private final String second;

		@Record
		public StoresRepeated(String first, String ignored) {
			this.first = first;
			this.second = first;
		}

		public String first() {
			return first;
		}

		public String second() {
			return second;
		}
	}
}
