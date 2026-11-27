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
import io.ltr8.annotation.FieldOrder;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Unbound;

import java.util.Objects;

/**
 * Classes whose {@code @FieldOrder} reverses their declared order.
 */
public final class FieldOrders {

	private FieldOrders() {
	}

	@FieldOrder({ "b", "a" })
	public record OrderedRecord(String a, int b) {
	}

	@FieldOrder({ "second", "first" })
	public record OrderedByJavaName(@Field("z") String first, @Field("y") String second) {
	}

	@FieldOrder({ "cache", "name" })
	public record OrderedWithUnbound(String name, @Unbound int cache) {
	}

	@FieldOrder({ "b", "a" })
	public static final class OrderedPublicFields {

		public String a;

		public int b;

		@Record
		public OrderedPublicFields() {
		}

		public OrderedPublicFields(String a, int b) {
			this.a = a;
			this.b = b;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof OrderedPublicFields other && Objects.equals(a, other.a) && b == other.b;
		}

		@Override
		public int hashCode() {
			return Objects.hash(a, b);
		}
	}
}
