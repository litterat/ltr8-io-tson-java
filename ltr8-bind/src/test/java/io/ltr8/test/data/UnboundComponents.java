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

import io.ltr8.annotation.Unbound;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Records holding a component the class owns, beside one the wire fills.
 */
public final class UnboundComponents {

	private UnboundComponents() {
	}

	public record UnboundBoolean(String name, @Unbound boolean cache) {
	}

	public record UnboundByte(String name, @Unbound byte cache) {
	}

	public record UnboundChar(String name, @Unbound char cache) {
	}

	public record UnboundShort(String name, @Unbound short cache) {
	}

	public record UnboundInt(String name, @Unbound int cache) {
	}

	public record UnboundLong(String name, @Unbound long cache) {
	}

	public record UnboundFloat(String name, @Unbound float cache) {
	}

	public record UnboundDouble(String name, @Unbound double cache) {
	}

	public record UnboundText(String name, @Unbound String cache) {
	}

	public record UnboundOptional(String name, @Unbound Optional<String> cache) {
	}

	public record UnboundOptionalInt(String name, @Unbound OptionalInt cache) {
	}

	/** The primitive the class owns comes first, so the bound component's place in the values differs from its own. */
	public record UnboundBeforeBound(@Unbound long cache, int count) {
	}
}
