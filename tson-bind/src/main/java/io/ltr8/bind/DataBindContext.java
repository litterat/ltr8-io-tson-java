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

import io.ltr8.annotation.DataBridge;
import io.ltr8.bind.analysis.DefaultClassBinder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DataBindContext {

	// Resolved class information
	private final ConcurrentHashMap<Type, DataClass> descriptors = new ConcurrentHashMap<>();

	// default resolver
	private final DefaultClassBinder dataClassResolver;

	// resolves a bare type name (e.g. a schema's own type name) to the Class getDescriptor(String) builds against
	private final DataNameBinder nameBinder;

	// This context's binding profile, selecting among a class's @Profile constructors. Empty for the
	// ordinary single-shape case, which is every context that has never needed to say otherwise.
	private final String profile;

	public static class Builder {

		boolean allowAny = false;

		boolean allowSerializable = false;

		Map<String, String> nameBinderAliases = Map.of();

		String profile = null;

		Set<String> nameBinderPackages = Set.of();

		DataNameBinder nameBinder = null;

		public Builder() {
		}

		public Builder allowAny() {
			allowAny = true;
			return this;
		}

		public Builder allowSerializable() {
			allowSerializable = true;
			return this;
		}

		/** Aliases consulted by the default {@link DataNameBinder} built when {@link #nameBinder(DataNameBinder)} isn't called. */
		public Builder nameBinderAliases(Map<String, String> aliases) {
			this.nameBinderAliases = aliases;
			return this;
		}

		/** Packages searched, in order, by the default {@link DataNameBinder} built when {@link #nameBinder(DataNameBinder)} isn't called. */
		public Builder nameBinderPackages(Set<String> packages) {
			this.nameBinderPackages = packages;
			return this;
		}

		/** Supplies a {@link DataNameBinder} directly, in place of the default {@link DataNameBinder.DefaultDataNameBinder}. */
		public Builder nameBinder(DataNameBinder nameBinder) {
			this.nameBinder = nameBinder;
			return this;
		}

		/**
		 * Names this context's binding profile, so a class carrying {@code @Profile} constructors is bound
		 * through the one that serves this name.
		 *
		 * <p><b>The point is one class, several shapes, several contexts.</b> A server speaking two versions
		 * of a schema at once builds a context per version; descriptors are cached per context, so each still
		 * maps a class to exactly one {@link DataClassRecord} -- the profile chooses which, once, rather than
		 * being consulted per value.
		 *
		 * <p>The name is opaque here: it is matched by equality and nothing in this module knows what it
		 * stands for. A class with no constructor for this profile falls back to its designated one, so
		 * naming a context costs nothing for the classes that do not care.
		 */
		public Builder profile(String profile) {
			this.profile = profile;
			return this;
		}

		public DataBindContext build() {
			return new DataBindContext(this);
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	private DataBindContext(Builder builder) {

		this.dataClassResolver = new DefaultClassBinder();
		this.profile = builder.profile;
		this.nameBinder = builder.nameBinder != null ? builder.nameBinder
				: new DataNameBinder.DefaultDataNameBinder(builder.nameBinderPackages, builder.nameBinderAliases);

		try {
			registerAtom(Boolean.class);
			registerAtom(boolean.class);
			registerAtom(Character.class);
			registerAtom(char.class);
			registerAtom(Byte.class);
			registerAtom(byte.class);
			registerAtom(Short.class);
			registerAtom(short.class);
			registerAtom(Integer.class);
			registerAtom(int.class);
			registerAtom(Long.class);
			registerAtom(long.class);
			registerAtom(Float.class);
			registerAtom(float.class);
			registerAtom(Double.class);
			registerAtom(double.class);
			registerAtom(BigInteger.class);
			registerAtom(BigDecimal.class);
			registerAtom(Void.class);
			registerAtom(String.class);
			registerAtom(Date.class);

		} catch (DataBindException e) {
			throw new IllegalArgumentException(e);
		}

	}

	/** This context's binding profile, empty when it has none -- see {@link Builder#profile}. */
	public Optional<String> profile() {
		return Optional.ofNullable(profile);
	}

	public DataClass getDescriptor(Class<?> targetClass) throws DataBindException {
		// Use the erased type if type parameters not provided.
		return getDescriptor(targetClass, targetClass);
	}

	/**
	 * The descriptor for {@code parameterizedType}, resolved and cached on first use.
	 *
	 * <p><b>A lost race is not an error here.</b> This is a lookup that fills a cache on a miss, so two
	 * threads asking for a type neither has seen both resolve it and both try to cache it -- and whichever
	 * arrives second takes the first one's descriptor rather than failing. Under {@link #register}, which
	 * throws on an already-registered type, the loser instead got "Class already registered", which a
	 * schemaless read surfaces as a {@code SCHEMA_ERROR} against a document that has nothing wrong with it.
	 * The two descriptors describe the same class, so which one wins does not matter; that they agree does,
	 * which is why the winner's is returned rather than this thread's own.
	 *
	 * <p>Resolution deliberately runs outside any lock: it recurses back into this method for every
	 * component type, so holding one across it would have to be reentrant over the whole graph, and the
	 * duplicated work on a race is one descriptor. {@code computeIfAbsent} is out for the same recursion --
	 * it is a documented deadlock/{@code IllegalStateException} on the map being computed.
	 *
	 * <p>{@link #register} and the {@code registerAtom} overloads stay strict: registering a type twice
	 * <em>explicitly</em> is still a caller error.
	 */
	public DataClass getDescriptor(Class<?> targetClass, Type parameterizedType) throws DataBindException {

		DataClass descriptor = descriptors.get(parameterizedType);
		if (descriptor != null) {
			return descriptor;
		}

		DataClass resolved = dataClassResolver.resolve(this, targetClass, parameterizedType);
		if (resolved == null) {
			throw new DataBindException(
					String.format("Unable to find suitable data descriptor for class: %s", targetClass.getName()));
		}

		DataClass winner = descriptors.putIfAbsent(parameterizedType, resolved);
		return winner != null ? winner : resolved;
	}

	/**
	 * Resolves {@code schemaTypeName} to a Java class via this context's own {@link DataNameBinder}
	 * (see {@link Builder#nameBinder}/{@link Builder#nameBinderPackages}/{@link
	 * Builder#nameBinderAliases}), then returns its descriptor exactly as {@link
	 * #getDescriptor(Class)} would -- one call in place of resolving the class and fetching its
	 * descriptor as two separate steps.
	 */
	public DataClass getDescriptor(String schemaTypeName) throws DataBindException {
		Class<?> target = nameBinder.resolve(schemaTypeName);
		return getDescriptor(target);
	}

	private <T> void checkExists(Type targetClass) throws DataBindException {
		if (descriptors.containsKey(targetClass)) {
			throw new DataBindException(String.format("Class already registered: %s", targetClass.getTypeName()));
		}
	}

	private <T> void register(Type targetClass, DataClass descriptor) throws DataBindException {
		checkExists(targetClass);

		descriptors.put(targetClass, descriptor);
	}


	public void registerAtom(Class<?> targetClass) throws DataBindException {
		register(targetClass, new DataClassAtom(targetClass));
	}

	public DataClassAtom registerAtom(Class<?> targetClass, DataBridge<?, ?> bridge) throws DataBindException {
		checkExists(targetClass);

		Class<?> bridgeClass = bridge.getClass();

		try {
			Method toDataMethod = bridgeClass.getMethod("toData", targetClass);
			Method toObjectMethod = bridgeClass.getMethod("toObject", toDataMethod.getReturnType());

			MethodHandle toData = MethodHandles.publicLookup().unreflect(toDataMethod).bindTo(bridge);
			MethodHandle toObject = MethodHandles.publicLookup().unreflect(toObjectMethod).bindTo(bridge);

			DataClassBridge dataClassBridge = new DataClassBridge(toDataMethod.getReturnType(), toData, toObject);
			DataClassAtom dataClass = new DataClassAtom(targetClass,dataClassBridge);
			register(targetClass, dataClass);
			return dataClass;
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | DataBindException e) {
			throw new DataBindException("Failed to register atom bridge", e);
		}

	}

}
