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
}
