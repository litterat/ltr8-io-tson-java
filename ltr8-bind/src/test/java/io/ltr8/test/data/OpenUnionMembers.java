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

import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Union;

import java.util.Optional;

/** Open unions, members that are described, and members refused after their own fields resolve. */
public final class OpenUnionMembers {

	private OpenUnionMembers() {
	}

	public static class Undescribable {
	}

	// --- an open interface union

	@Union
	public interface Shape {
	}

	public record Point(int x, int y) implements Shape {
	}

	/** Good on its own terms, but on a cycle with the refused {@link CircleHolder}. */
	public record Circle(String tag, Optional<CircleHolder> holder) implements Shape {
	}

	/** Refused at its second field, after {@link Circle} resolves beneath it. */
	public record CircleHolder(Circle circle, Undescribable value) {
	}

	/** A member on a good cycle, described with {@link SquareHolder}. */
	public record Square(String tag, Optional<SquareHolder> holder) implements Shape {
	}

	public record SquareHolder(Square square) {
	}

	public record Line(int length) implements Shape {
	}

	// --- an open abstract class union

	@Union
	public abstract static class Figure {
	}

	/** {@link Circle}, reaching its union through its super class. */
	public static class Triangle extends Figure {

		private final String tag;
		private final Optional<TriangleHolder> holder;

		@Record
		public Triangle(String tag, Optional<TriangleHolder> holder) {
			this.tag = tag;
			this.holder = holder;
		}

		public String tag() {
			return tag;
		}

		public Optional<TriangleHolder> holder() {
			return holder;
		}
	}

	public record TriangleHolder(Triangle triangle, Undescribable value) {
	}

	// --- a @Union this context does not describe as a union

	/** Refused: an interface has no constructor to bind through. */
	@Union
	@Record
	public interface UndescribableUnion {
	}

	public record OfUndescribableUnion(String tag) implements UndescribableUnion {
	}

	/** Described as a record, not a union. */
	@Union
	@Record
	public abstract static class RecordShapedUnion {

		public RecordShapedUnion() {
		}
	}

	public static class OfRecordShapedUnion extends RecordShapedUnion {

		@Record
		public OfRecordShapedUnion() {
		}
	}

	// --- a @Union whose description throws an Error

	/** Its class cannot be initialised, so building it throws {@link ExceptionInInitializerError}. */
	public static class UninitialisableBridge implements DataBridge<String, String> {

		static {
			fail();
		}

		private static void fail() {
			throw new IllegalStateException("this bridge has no class");
		}

		@Override
		public String toData(String b) {
			return b;
		}

		@Override
		public String toObject(String s) {
			return s;
		}
	}

	/** Described as a record whose bridged field cannot be built. */
	@Union
	@Record
	public abstract static class BrokenUnion {

		private String value;

		public BrokenUnion() {
		}

		@Field(bridge = UninitialisableBridge.class)
		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}
	}

	public static class OfBrokenUnion extends BrokenUnion {

		@Record
		public OfBrokenUnion() {
		}
	}
}
