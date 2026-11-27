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

import io.ltr8.test.data.union.SealedInterfaceUnion;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classes a union read may be handed the name of, each recording in {@link #INITIALISED} when it is initialised. A
 * test names them by string only, and nothing else in the suite initialises them.
 */
public final class UnionTripwires {

	private UnionTripwires() {
	}

	public static final Set<String> INITIALISED = ConcurrentHashMap.newKeySet();

	/** Related to no union. */
	public static final class Unrelated {

		static {
			INITIALISED.add("Unrelated");
		}
	}

	/** Of the type of a {@code @Union} that lists its members, and not listed by it. */
	public record ListedOut(int x, int y) implements SealedInterfaceUnion {

		static {
			INITIALISED.add("ListedOut");
		}
	}

	public static final String UNRELATED = UnionTripwires.class.getName() + "$Unrelated";

	public static final String LISTED_OUT = UnionTripwires.class.getName() + "$ListedOut";
}
