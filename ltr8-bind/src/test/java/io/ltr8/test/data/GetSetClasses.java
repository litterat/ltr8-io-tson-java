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

/**
 * Hand-written {@code @Record} classes reaching components through public fields and getter/setter pairs, one shape
 * each. A class beside the shape it tests holds a public field {@code name}, so it has a component either way.
 */
public final class GetSetClasses {

	private GetSetClasses() {
	}

	// --- public fields

	public static class FinalFieldWithAccessor {

		public final String name;

		@Record
		public FinalFieldWithAccessor(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class FinalFieldBesideMutableField {

		public final String name;

		public int count;

		@Record
		public FinalFieldBesideMutableField(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class FinalFieldWithoutAccessor {

		public final String name;

		@Record
		public FinalFieldWithoutAccessor(String name) {
			this.name = name;
		}
	}

	public static class InlineFinalField {

		public final String fixed = "fixed";

		public String name;

		@Record
		public InlineFinalField() {
		}
	}

	public static class MutableFields {

		public String name;

		public int count;

		@Record
		public MutableFields() {
		}
	}

	public static class MutableFieldBoundByConstructor {

		public String name;

		@Record
		public MutableFieldBoundByConstructor(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class StaticField {

		public static String shared;

		public String name;

		@Record
		public StaticField() {
		}
	}
}
