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

import java.util.List;

/**
 * A two-level sealed hierarchy: {@code Shape} permits one directly-concrete member ({@code
 * DirectPoint}) and one further-sealed member ({@code Branch}), which itself permits two concrete
 * leaves. Exercises {@code DefaultUnionBinder}'s recursive flattening -- {@code Shape}'s own union
 * members must resolve to {@code DirectPoint}/{@code LeafA}/{@code LeafB}, never {@code Branch}
 * itself, which isn't directly instantiable.
 */
public class NestedSealedRecord {

	public sealed interface Shape permits DirectPoint, Branch {
	}

	public record DirectPoint(int x, int y) implements Shape {
	}

	public sealed interface Branch extends Shape permits LeafA, LeafB {
	}

	public record LeafA(int a) implements Branch {
	}

	public record LeafB(int b) implements Branch {
	}

	public record ShapeList(List<Shape> list, Shape[] array) {
	}
}
