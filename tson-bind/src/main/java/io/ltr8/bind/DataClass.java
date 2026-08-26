/*
 * Copyright (c) 2020, Litterat Pty Ltd. All Rights Reserved.
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

import java.util.Objects;
import java.util.Optional;
import io.ltr8.bind.internal.Memoized;

/**
 * 
 * A DataClass represents the interface into data classes. This is also the parent class for
 * DataClassAtom, DataClassRecord, DatClassArray and DataClassUnion.
 *
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public abstract class DataClass {

	// The application type data class.
	private final Class<?> typeClass;

	private final Optional<DataClassBridge> bridge;

	/**
	 * A held component descriptor's class name for a {@code toString}, without resolving one that is still
	 * deferred -- pulling a descriptor as a side effect of printing one would be a trap in exactly the place
	 * a trap is hardest to see.
	 *
	 * <p>Package visible rather than protected: both callers are descriptors in this package, and a
	 * {@code protected} signature would put {@link Memoized}, which this module does not export, into the
	 * surface a subclass outside it compiles against.
	 */
	static String shown(Memoized<DataClass> held) {
		DataClass known = held.peek();
		return known != null ? known.typeClass().getName() : "<deferred>";
	}

	public DataClass( Class<?> targetType, DataClassBridge bridge) {
		this.typeClass = Objects.requireNonNull(targetType);
		this.bridge = Optional.ofNullable(bridge);
	}

	public DataClass( Class<?> targetType) {
		this(targetType, null);
	}

	/**
	 * @return The class this descriptor is for.
	 */
	public Class<?> typeClass() {
		return typeClass;
	}

	public Optional<DataClassBridge> bridge() {
		return bridge;
	}

	public Class<?> dataClass() {
		if (bridge().isPresent()) {
			return bridge().get().dataClass();
		}
		return typeClass;
	}
}
