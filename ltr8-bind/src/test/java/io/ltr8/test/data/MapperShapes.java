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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Records read and written by both mappers, for the shapes a read can be handed that the class does not fit.
 */
public final class MapperShapes {

	private MapperShapes() {
	}

	public record Point(int x, int y) {
	}

	public record Triple(int a, String b, long c) {
	}

	public record HoldsTriple(String name, Triple triple) {
	}

	public record OptionalList(Optional<List<String>> names) {
	}

	public record OptionalArray(Optional<String[]> names) {

		@Override
		public boolean equals(Object other) {
			return other instanceof OptionalArray(Optional<String[]> those)
					&& names.isPresent() == those.isPresent()
					&& (names.isEmpty() || Arrays.equals(names.get(), those.get()));
		}

		@Override
		public int hashCode() {
			return names.map(Arrays::hashCode).orElse(0);
		}
	}

	public record OptionalRecord(Optional<Point> point) {
	}

	public record NullableComponents(List<String> names, int[] counts, Point point) {

		@Override
		public boolean equals(Object other) {
			return other instanceof NullableComponents(List<String> n, int[] c, Point p)
					&& Objects.equals(names, n) && Arrays.equals(counts, c) && Objects.equals(point, p);
		}

		@Override
		public int hashCode() {
			return Objects.hash(names, Arrays.hashCode(counts), point);
		}
	}
}
