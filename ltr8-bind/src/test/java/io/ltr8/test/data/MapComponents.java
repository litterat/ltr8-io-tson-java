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

import io.ltr8.annotation.AnnotatedMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;

/**
 * Records with a map component, of each kind of key and value.
 */
public final class MapComponents {

	private MapComponents() {
	}

	public record Point(int x, int y) {
	}

	public record AtomValues(Map<String, Integer> counts) {
	}

	public record RecordValues(Map<String, Point> points) {
	}

	public record RecordKeys(Map<Point, String> labels) {
	}

	public record EnumKeys(Map<SimpleEnum, Integer> ranks) {
	}

	public record BridgedKeys(Map<UUID, String> owners) {
	}

	public record ListValues(Map<String, List<Integer>> series) {
	}

	public record MapValues(Map<String, Map<String, Integer>> nested) {
	}

	public record OptionalMap(Optional<Map<String, Integer>> counts) {
	}

	public record OrderedValues(SequencedMap<String, Integer> ranked) {
	}

	public record HoldsMapRecords(List<RecordValues> items) {
	}

	public record AnnotatedKeys(AnnotatedMap<String, Integer> counts) {
	}

	public record AnnotatedRecordKeys(AnnotatedMap<Point, String> labels) {
	}
}
