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
import io.ltr8.annotation.Tuple;
import io.ltr8.test.data.union.InterfaceUnion;
import io.ltr8.test.data.union.OpenBranchUnion;
import io.ltr8.test.data.union.SealedInterfaceUnion;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

	public record ListOfRecords(List<Point> points) {
	}

	public record ArrayOfRecords(Point[] points) {

		@Override
		public boolean equals(Object other) {
			return other instanceof ArrayOfRecords(Point[] those) && Arrays.equals(points, those);
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(points);
		}
	}

	public record SetOfRecords(Set<Point> points) {
	}

	public record ListOfText(List<String> names) {
	}

	public record ListOfLists(List<List<String>> rows) {
	}

	public record ListOfMaps(List<Map<String, Integer>> counts) {
	}

	public record ListOfUnion(List<SealedInterfaceUnion> shapes) {
	}

	public record ListOfOpenUnion(List<InterfaceUnion> shapes) {
	}

	public record ListOfOpenBranch(List<OpenBranchUnion> shapes) {
	}

	/** A union whose members include ones with a field named {@code type}, the key {@code MapMapper} tags with. */
	public sealed interface Labelled permits Tagged, Renamed, Untagged {
	}

	public record Tagged(String type, int size) implements Labelled {
	}

	public record Renamed(@Field("type") String kind) implements Labelled {
	}

	public record Untagged(int size) implements Labelled {
	}

	public record ListOfLabelled(List<Labelled> items) {
	}

	public record Required(int count, @Field(required = true) String name, Optional<String> note, String label) {
	}

	public record HoldsRequired(String id, Required required) {
	}

	public record ListOfRequired(List<Required> items) {
	}

	public record MapOfRequired(Map<String, Required> items) {
	}

	public record ListOfTriples(List<Triple> triples) {
	}

	@Tuple
	public record Pair(String name, int count) {
	}

	@Tuple
	public record NestedPair(Pair inner, long id) {
	}

	public record HoldsPair(String label, Pair pair) {
	}

	public record ListOfPairs(List<Pair> pairs) {
	}
}
