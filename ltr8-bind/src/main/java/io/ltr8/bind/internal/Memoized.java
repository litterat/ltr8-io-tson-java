/*
 * Copyright (c) 2021, Litterat Pty Ltd. All Rights Reserved.
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
package io.ltr8.bind.internal;

import java.util.function.Supplier;

/**
 * A value known at construction, or one pulled once on first use and kept.
 *
 * <p><b>Internal.</b> This package is not exported: the class exists for the descriptor holders' benefit
 * and is no part of what a consumer of this module binds against.
 *
 * <p>Exists so that the descriptor holders can keep their component's descriptor in a {@code final} field
 * even when it cannot be resolved yet -- a cyclic type graph has one edge that must wait for the resolution
 * it points back into to finish. The alternative, a non-final field and a null check in each accessor, would
 * repeat one lazy-initialisation idiom across every holder and give up safe publication at each of them; the
 * point of this class is that the idiom is written once and reviewed once.
 *
 * <p><b>Racy on purpose, and safe because of what it holds.</b> Two threads may both find the value absent
 * and both pull it, and neither is wrong to: a deferred descriptor is fetched from {@code DataBindContext}'s
 * own descriptor cache, so both pulls return the same instance and the second write stores what the first
 * did. {@code volatile} is what makes that sound rather than merely likely -- it publishes whatever the
 * supplier returned, so a thread that reads a non-null value reads a fully constructed one. No lock is
 * needed, and none is taken on the overwhelmingly common path where the value was known all along.
 */
public final class Memoized<T> {

	/** {@code null} when the value was known at construction -- including when that value is itself null. */
	private final Supplier<T> source;

	private volatile T value;

	private Memoized(Supplier<T> source, T value) {
		this.source = source;
		this.value = value;
	}

	/** A value already in hand. {@code null} is a legitimate one: a component may have no descriptor. */
	public static <T> Memoized<T> of(T value) {
		return new Memoized<>(null, value);
	}

	/** A value to be pulled on first use -- see this class's own Javadoc for when that is necessary. */
	public static <T> Memoized<T> deferred(Supplier<T> source) {
		return new Memoized<>(source, null);
	}

	public T get() {
		if (source == null) {
			return value;
		}
		T known = value;
		if (known == null) {
			known = source.get();
			value = known;
		}
		return known;
	}

	/**
	 * The value if it is already known, else {@code null} -- for a caller that must not cause a pull, which
	 * is every {@code toString}: resolving a deferred descriptor as a side effect of printing one would be a
	 * trap, and a diagnostic that says {@code <deferred>} is the honest answer at that moment.
	 */
	public T peek() {
		return value;
	}

	@Override
	public String toString() {
		// Deliberately does not pull: a holder's toString is called in diagnostics and while debugging, and
		// resolving a deferred descriptor as a side effect of printing one would be a trap.
		T known = value;
		return known != null ? String.valueOf(known) : (source == null ? "null" : "<deferred>");
	}
}
