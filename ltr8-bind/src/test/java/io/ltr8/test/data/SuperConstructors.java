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

import io.ltr8.annotation.Record;

import java.util.Objects;
import java.util.Optional;

/**
 * Hand-written immutable classes whose constructors hand arguments, unchanged, to a public super class constructor.
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
}
