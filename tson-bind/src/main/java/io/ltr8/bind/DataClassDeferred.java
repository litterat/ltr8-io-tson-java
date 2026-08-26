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
package io.ltr8.bind;

import java.lang.reflect.Type;

/**
 * A placeholder for a descriptor that is still being built -- the one edge of a cyclic type graph that
 * cannot be resolved eagerly, because resolving it is what is already in progress.
 *
 * <p><b>Only ever handed out by {@link DataBindContext#getDescriptor} on re-entry</b>, and only into a
 * holder that settles it before anyone sees it: {@link DataClass#settled} is what every accessor holding a
 * descriptor calls, so a consumer never meets one of these and never has to know a cycle was involved. That
 * is what keeps the repair invisible to pattern matching -- a caller switching on {@code DataClassRecord} /
 * {@code DataClassArray} sees the real descriptor, which a general lazy proxy could not have offered.
 *
 * <p><b>By the time it settles, the cache has the answer.</b> A cyclic edge is reached during resolution and
 * used after it, so {@link #settled()} finds the completed descriptor and memoises it. Resolution of a
 * component that is <em>not</em> part of a cycle is untouched and still eager, so a component type that
 * cannot be described still fails while the descriptor is being built rather than at first use.
 */
final class DataClassDeferred extends DataClass {

	private final DataBindContext context;
	private final Type parameterizedType;

	private DataClass settled;

	DataClassDeferred(DataBindContext context, Class<?> targetType, Type parameterizedType) {
		super(targetType);
		this.context = context;
		this.parameterizedType = parameterizedType;
	}

	/**
	 * The descriptor this stands for, once it exists.
	 *
	 * <p>Not synchronised: a lost race resolves the same cached descriptor twice and stores the same
	 * reference, exactly as {@link DataBindContext#getDescriptor}'s own cache fill does and for the same
	 * reason -- what matters is that the two agree, and they are the same object.
	 */
	DataClass settled() {
		DataClass known = settled;
		if (known != null) {
			return known;
		}
		try {
			DataClass resolved = context.getDescriptor(typeClass(), parameterizedType);
			if (resolved instanceof DataClassDeferred) {
				// Reachable only if a descriptor is asked for its components while it is still being built,
				// which nothing in this module does: the cycle is closed during resolution and read after it.
				throw new IllegalStateException("descriptor for " + typeClass().getName()
						+ " is still being resolved, so its cyclic edge cannot be settled yet");
			}
			settled = resolved;
			return resolved;
		} catch (DataBindException e) {
			// The type resolved once already -- reaching this edge means it is in the cache -- so a failure
			// here is not the caller's to handle and has nowhere useful to be declared.
			throw new IllegalStateException("failed to settle the deferred descriptor for "
					+ typeClass().getName(), e);
		}
	}
}
