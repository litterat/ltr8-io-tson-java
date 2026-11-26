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

/**
 * Hand-written mutable classes whose public fields are declared on a super class.
 */
public final class InheritedFields {

	private InheritedFields() {
	}

	public static class Labels {

		public String label;

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(label, ((Labels) obj).label);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(label);
		}
	}

	@Record
	public static class Weighed extends Labels {

		public int weight;

		public static Weighed of(String label, int weight) {
			Weighed weighed = new Weighed();
			weighed.label = label;
			weighed.weight = weight;
			return weighed;
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj) && weight == ((Weighed) obj).weight;
		}

		@Override
		public int hashCode() {
			return 31 * super.hashCode() + weight;
		}
	}

	public record HoldsWeighed(Weighed value) {
	}
}
