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
import io.ltr8.bind.analysis.ClassBinder;
import io.ltr8.bind.analysis.RecordBinder;
import io.ltr8.bind.analysis.ProtocolBinder;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;

public class DataBindContext {

	// Resolved class information
	private final ConcurrentHashMap<Type, DataClass> descriptors = new ConcurrentHashMap<>();

	/** Held only while {@link #publish} caches a component; readers of {@link #descriptors} never take it. */
	private final Object publishing = new Object();

	private final ConcurrentHashMap<Class<?>, DataProtocol<?>> protocols = new ConcurrentHashMap<>();

	private final ProtocolBinder protocolBinder = new ProtocolBinder();

	/**
	 * What the call now running is part-way through resolving: bound by the outermost {@link #getDescriptor} for
	 * as long as it runs, so nothing stays on the thread after it. Scoped and not passed, because a binder calls
	 * back into this context and not into the call. Two threads resolving the same cyclic type each bind their
	 * own, build a complete descriptor, and one of them wins the cache, exactly as for an acyclic one.
	 *
	 * <p>A thread forked in a {@code StructuredTaskScope} inherits the binding and a {@link Resolution} is one
	 * thread's, so a binder never forks a thread that calls back here.
	 */
	private final ScopedValue<Resolution> resolution = ScopedValue.newInstance();

	/**
	 * The resolutions one thread has in flight, nested one per {@link #getDescriptor} call, and the descriptors
	 * resolved beneath them that are not yet cached.
	 *
	 * <p><b>A descriptor enters the cache only once every descriptor its deferred edges reach is there with
	 * it.</b> A descriptor holding a deferred edge to a type still in flight is complete only if that type's
	 * resolution succeeds, so it is held here until then -- cached with it, or discarded with it. Cached any
	 * earlier, a refused type would leave behind a descriptor that is handed out and then fails when its edge is
	 * pulled, and whether a class is refused would depend on which class was asked for first. This is Tarjan's
	 * strongly-connected-component walk: a resolution whose descriptors reach no type in flight above it closes
	 * its component, and only then is the component cached. A descriptor reaching no cycle closes its own at once
	 * and is cached as soon as it resolves, so holding back is confined to the cycle.
	 *
	 * <p>A closed component is cached whole ({@link #publish}), so another thread sees all of it or none, and
	 * every descriptor a cached one holds is the one the cache hands out for its class.
	 */
	private static final class Resolution {

		/** The depth of each type in flight, 0 for the one a caller asked for. */
		private final Map<Type, Integer> depths = new HashMap<>();

		/** Per depth, the shallowest depth in flight that a descriptor resolved beneath it reaches. */
		private final List<Integer> lowLinks = new ArrayList<>();

		/** Each held descriptor and the shallowest depth in flight it reaches. */
		private final Map<Type, Held> held = new HashMap<>();

		/** The held types in the order they resolved, so a resolution's own are those after its mark. */
		private final List<Type> heldOrder = new ArrayList<>();

		private record Held(DataClass descriptor, int lowLink) {
		}

		private int mark() {
			return heldOrder.size();
		}

		private int enter(Type type) {
			int depth = lowLinks.size();
			depths.put(type, depth);
			lowLinks.add(depth);
			return depth;
		}

		private int exit(Type type) {
			depths.remove(type);
			return lowLinks.removeLast();
		}

		/** Records that the resolution now innermost reaches the type in flight at {@code depth}. */
		private void reach(int depth) {
			if (!lowLinks.isEmpty() && depth < lowLinks.getLast()) {
				lowLinks.set(lowLinks.size() - 1, depth);
			}
		}

		/**
		 * Holds {@code descriptor}, and moves everything held since {@code mark} to its low link: they are
		 * in its component now, and must not be taken as closing one of their own when met again.
		 */
		private void hold(int mark, Type type, DataClass descriptor, int lowLink) {
			for (Type since : heldOrder.subList(mark, heldOrder.size())) {
				held.put(since, new Held(held.get(since).descriptor(), lowLink));
			}
			held.put(type, new Held(descriptor, lowLink));
			heldOrder.add(type);
		}

		private Map<Type, DataClass> release(int mark) {
			List<Type> since = heldOrder.subList(mark, heldOrder.size());
			Map<Type, DataClass> released = new LinkedHashMap<>();
			for (Type type : since) {
				released.put(type, held.remove(type).descriptor());
			}
			since.clear();
			return released;
		}
	}

	// default resolver
	private final ClassBinder dataClassResolver;

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

		final List<Registration> atoms = new ArrayList<>();

		/** One accumulated {@link #registerAtom} call, applied when the context is constructed. */
		record Registration(Class<?> targetClass, DataBridge<?, ?> bridge) {
		}

		public Builder() {
		}

		/**
		 * Binds {@code targetClass} as an <b>atom</b> -- one scalar on the wire -- rather than letting it be
		 * taken apart structurally. What a class whose values have no useful parts needs: a {@code UUID} is
		 * not a record of its bits, and a Java record whose values are one token would otherwise be written
		 * as its components.
		 *
		 * <p>The class is read as describing it reads it: an enum or a class stating its own {@code @Atom} form
		 * keeps that form, and a mark the atom form does not read -- {@code @Record}, {@code @Tuple},
		 * {@code @Transparent}, {@code @Union}, {@code @FieldOrder}, {@code ToData} on a class marking no
		 * {@code @Atom} -- refuses the context when it is built.
		 */
		public Builder registerAtom(Class<?> targetClass) {
			atoms.add(new Registration(targetClass, null));
			return this;
		}

		/**
		 * Binds {@code targetClass} as an atom whose wire form is the type {@code bridge} converts to and
		 * from -- for a class the engine cannot read directly, where the bridge names the crossing.
		 *
		 * <p>The bridge overrides the class: whatever binding marks it carries are set aside, unread and
		 * unrefused, so a record or an enum may be registered as an atom of another form this way.
		 */
		public Builder registerAtom(Class<?> targetClass, DataBridge<?, ?> bridge) {
			atoms.add(new Registration(targetClass, Objects.requireNonNull(bridge, "bridge")));
			return this;
		}

		/**
		 * {@link #registerAtom(Class)} for each of {@code atomTypes}, in order -- for a caller whose atom
		 * vocabulary is a list it holds rather than a chain it writes. A named list reads as the vocabulary
		 * it is and can be asserted against; eleven chained calls read as eleven decisions.
		 */
		public Builder registerAtoms(List<Class<?>> atomTypes) {
			atomTypes.forEach(this::registerAtom);
			return this;
		}

		/** Whether {@code targetClass} is registered as an atom by this builder, with or without a bridge. */
		public boolean registersAtom(Class<?> targetClass) {
			return atoms.stream().anyMatch(atom -> atom.targetClass().equals(targetClass));
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

	// What this context was built from, so a caller can derive a context that adds to it (toBuilder).
	private final boolean allowAny;
	private final boolean allowSerializable;
	private final List<Builder.Registration> atoms;

	private DataBindContext(Builder builder) {
		this.allowAny = builder.allowAny;
		this.allowSerializable = builder.allowSerializable;
		this.atoms = List.copyOf(builder.atoms);

		this.dataClassResolver = new ClassBinder();
		this.profile = builder.profile;
		this.nameBinder = builder.nameBinder != null ? builder.nameBinder
				: new DataNameBinder.DefaultDataNameBinder(builder.nameBinderPackages, builder.nameBinderAliases);

		try {
			registerCoreAtom(Boolean.class);
			registerCoreAtom(boolean.class);
			registerCoreAtom(Character.class);
			registerCoreAtom(char.class);
			registerCoreAtom(Byte.class);
			registerCoreAtom(byte.class);
			registerCoreAtom(Short.class);
			registerCoreAtom(short.class);
			registerCoreAtom(Integer.class);
			registerCoreAtom(int.class);
			registerCoreAtom(Long.class);
			registerCoreAtom(long.class);
			registerCoreAtom(Float.class);
			registerCoreAtom(float.class);
			registerCoreAtom(Double.class);
			registerCoreAtom(double.class);
			registerCoreAtom(BigInteger.class);
			registerCoreAtom(BigDecimal.class);
			registerCoreAtom(Void.class);
			registerCoreAtom(String.class);
			registerCoreAtom(Date.class);

			// The builder's own, after the primitives, so a caller registering one of those gets the
			// "already registered" error rather than silently shadowing it.
			for (Builder.Registration atom : builder.atoms) {
				if (atom.bridge() == null) {
					registerAtom(atom.targetClass());
				} else {
					registerAtom(atom.targetClass(), atom.bridge());
				}
			}

		} catch (DataBindException e) {
			throw new IllegalArgumentException(e);
		}

	}

	/**
	 * A builder holding everything this context was built from -- its name binder, profile, permissions and atom
	 * registrations -- so a caller can build a context that adds to this one rather than restating it.
	 */
	public Builder toBuilder() {
		Builder builder = new Builder();
		builder.allowAny = allowAny;
		builder.allowSerializable = allowSerializable;
		builder.profile = profile;
		builder.nameBinder = nameBinder;
		builder.atoms.addAll(atoms);
		return builder;
	}

	/** The binder this context resolves a type name to a class with ({@link #getDescriptor(String, String)}). */
	public DataNameBinder nameBinder() {
		return nameBinder;
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
	 * duplicated work on a race is one descriptor, or one cycle's. {@code computeIfAbsent} is out for the same recursion --
	 * it is a documented deadlock/{@code IllegalStateException} on the map being computed.
	 *
	 * <p><b>A refusal is not cached</b>: asking again resolves again and is refused again. Nor is any descriptor
	 * resolved on the way that reaches the refused type, so whether a class is refused does not depend on which
	 * class was asked for first.
	 *
	 * <p>{@link #register} and the {@code registerAtom} overloads stay strict: registering a type twice
	 * <em>explicitly</em> is still a caller error.
	 */
	public DataClass getDescriptor(Class<?> targetClass, Type parameterizedType) throws DataBindException {

		DataClass descriptor = descriptors.get(parameterizedType);
		if (descriptor != null) {
			return descriptor;
		}
		if (parameterizedType != targetClass && descriptors.get(targetClass) instanceof DataClassAtom atom) {
			// A registered atom serves every parameterisation of its class: its wire form is one token whatever
			// the type argument says about the value it stands for (a reference's referent, say).
			return atom;
		}

		if (!resolution.isBound()) {
			return ScopedValue.where(resolution, new Resolution())
					.call(() -> getDescriptor(targetClass, parameterizedType));
		}
		Resolution resolving = resolution.get();
		Resolution.Held held = resolving.held.get(parameterizedType);
		if (held != null) {
			resolving.reach(held.lowLink());
			return held.descriptor();
		}
		if (resolving.depths.containsKey(parameterizedType)) {
			throw new DataBindException(String.format(
					"Descriptor for %s is already being resolved on this thread: its type graph contains a "
							+ "cycle, so this component must be taken through componentSource rather than "
							+ "resolved here",
					targetClass.getName()));
		}

		int mark = resolving.mark();
		int depth = resolving.enter(parameterizedType);
		DataClass resolved = null;
		int lowLink;
		try {
			resolved = dataClassResolver.resolve(this, targetClass, parameterizedType);
		} finally {
			lowLink = resolving.exit(parameterizedType);
			if (resolved == null) {
				resolving.release(mark);
			}
		}
		if (resolved == null) {
			throw new DataBindException(
					String.format("Unable to find suitable data descriptor for class: %s", targetClass.getName()));
		}

		if (lowLink < depth) {
			resolving.hold(mark, parameterizedType, resolved, lowLink);
			resolving.reach(lowLink);
			return resolved;
		}
		Map<Type, DataClass> members = resolving.release(mark);
		// Joined only now the component has resolved whole, so a refused class is never a union's member; and
		// before it is cached, so the class of every cached descriptor already is.
		RecordBinder.joinUnions(this, resolved);
		for (DataClass member : members.values()) {
			RecordBinder.joinUnions(this, member);
		}
		DataClass published = publish(parameterizedType, resolved, members);
		return published != null ? published : getDescriptor(targetClass, parameterizedType);
	}

	/**
	 * Caches the component {@code root} closes -- {@code root} and the {@code members} held beneath it -- all of
	 * it or none, and returns what the cache holds for {@code root}; {@code null} where the component is dropped
	 * and {@code root} must be resolved again.
	 *
	 * <p><b>All or none, because the members were built over each other.</b> Cached one at a time, two threads
	 * closing one component could each win part of it, leaving the cache holding one thread's descriptor for a
	 * class and the other's descriptor holding a second one. Under {@link #publishing}, which no resolve runs
	 * inside, a component is either cached whole or not at all.
	 *
	 * <p>Two threads closing one component close the same members: a strongly connected component is fixed by
	 * the type graph, not by the member a walk enters it from, and a walk builds every member not already
	 * cached. So {@code root} cached means the whole component is, and this thread's is dropped for the cache's,
	 * as a lost race always was. A member cached while {@code root} is not means the two walks saw different
	 * graphs. This component then holds a second descriptor for that member, so it is dropped and {@code root}
	 * resolved again, over the cached member; each retry finds a type more in the cache, so it ends.
	 */
	private DataClass publish(Type root, DataClass resolved, Map<Type, DataClass> members) {
		synchronized (publishing) {
			DataClass cached = descriptors.get(root);
			if (cached != null) {
				return cached;
			}
			for (Type member : members.keySet()) {
				if (descriptors.containsKey(member)) {
					return null;
				}
			}
			descriptors.putAll(members);
			descriptors.put(root, resolved);
			return resolved;
		}
	}

	/**
	 * The interface {@code type} described as the exchanges a call arriving as data is dispatched to: its abstract
	 * instance methods, inherited ones included, other than those with the signature of {@code equals}, {@code
	 * hashCode} or {@code toString}. Each takes one record or nothing, returns a value this context describes or
	 * nothing, and declares as errors only classes that implement {@code ToData<S>} with a public constructor
	 * taking {@code S}. Analysed whole on the first call and cached, so every call for one interface returns the
	 * same instance; a lost race returns the winner's, as {@link #getDescriptor(Class, Type)} does.
	 *
	 * @param type a public interface, in a package its module exports to every module
	 * @throws DataBindException where {@code type} is not such an interface, or a method of it is not an exchange;
	 *                           the message names the interface, the method and what is wrong
	 */
	@SuppressWarnings("unchecked")
	public <T> DataProtocol<T> getProtocol(Class<T> type) throws DataBindException {
		DataProtocol<?> known = protocols.get(Objects.requireNonNull(type, "type"));
		if (known != null) {
			return (DataProtocol<T>) known;
		}
		DataProtocol<T> built = new DataProtocol<>(type, protocolBinder.resolve(this, type));
		DataProtocol<?> winner = protocols.putIfAbsent(type, built);
		return winner != null ? (DataProtocol<T>) winner : built;
	}

	/**
	 * The descriptor for a <em>component</em> of the type being described -- a field's type, an array's
	 * element, a map's key or value -- as something the holder resolves rather than as the descriptor itself.
	 *
	 * <p><b>A type graph may contain a cycle</b>: a record reaching itself, directly or through others.
	 * Resolution follows the types rather than a value, so it has no reason of its own to stop, and the cache
	 * cannot help because it is filled once resolution <em>completes</em> -- which for a cyclic type is never.
	 * A component on such an edge is therefore taken as a supplier, resolved by its holder after the
	 * resolution now in progress has finished and the cache has the answer.
	 *
	 * <p><b>Only the cyclic edge is deferred.</b> Every other component is resolved here and handed back as a
	 * constant, so a component type that cannot be described still fails while the descriptor is being built
	 * rather than at first use. Which of the two a caller gets is not its concern: both are a supplier, and
	 * the holder memoises what it pulls.
	 */
	public Supplier<DataClass> componentSource(Class<?> targetClass, Type parameterizedType)
			throws DataBindException {

		Resolution resolving = resolution.isBound() ? resolution.get() : null;
		Integer depth = resolving != null ? resolving.depths.get(parameterizedType) : null;
		if (depth != null) {
			resolving.reach(depth);
			return () -> {
				try {
					return getDescriptor(targetClass, parameterizedType);
				} catch (DataBindException e) {
					// The type resolved once already -- reaching this edge means it is in the cache -- so a
					// failure here is not the holder's to handle and has nowhere useful to be declared.
					throw new IllegalStateException(
							"failed to resolve the deferred descriptor for " + targetClass.getName(), e);
				}
			};
		}
		DataClass resolved = getDescriptor(targetClass, parameterizedType);
		return () -> resolved;
	}

	/**
	 * Resolves {@code schemaTypeName} to a Java class via this context's own {@link DataNameBinder}
	 * (see {@link Builder#nameBinder}/{@link Builder#nameBinderPackages}/{@link
	 * Builder#nameBinderAliases}), asked by the name alone, then returns its descriptor exactly as {@link
	 * #getDescriptor(Class)} would. For a caller with one namespace; {@link #getDescriptor(String, String)} asks
	 * within the namespace declaring the type.
	 *
	 * @throws UnboundNameException where the binder resolves the name to no class
	 * @throws DataBindException    where it does, and that class cannot be analysed -- the message names both
	 */
	public DataClass getDescriptor(String schemaTypeName) throws DataBindException {
		Class<?> target;
		try {
			target = nameBinder.resolve(schemaTypeName);
		} catch (DataBindException e) {
			throw new UnboundNameException(e.getMessage(), e);
		}
		return describe(schemaTypeName, target);
	}

	/**
	 * The descriptor of the class the type {@code schemaTypeName}, declared in {@code namespace}, binds to under
	 * this context's {@link DataNameBinder} ({@link DataNameBinder#resolve(String, String)}) -- one call in place
	 * of resolving the class and fetching its descriptor as two separate steps.
	 *
	 * @param namespace the namespace declaring the type, opaque and matched by equality
	 * @throws UnboundNameException where the binder resolves the type to no class
	 * @throws DataBindException    where it does, and that class cannot be analysed -- the message names both
	 */
	public DataClass getDescriptor(String namespace, String schemaTypeName) throws DataBindException {
		Class<?> target;
		try {
			target = nameBinder.resolve(namespace, schemaTypeName);
		} catch (DataBindException e) {
			throw new UnboundNameException(e.getMessage(), e);
		}
		return describe(schemaTypeName, target);
	}

	private DataClass describe(String schemaTypeName, Class<?> target) throws DataBindException {
		try {
			return getDescriptor(target);
		} catch (DataBindException e) {
			throw new DataBindException("'" + schemaTypeName + "' binds " + target.getName()
					+ ", which cannot be analysed: " + reasons(e), e);
		}
	}

	/**
	 * Each distinct message along {@code e}'s cause chain, outermost first: a wrapper saying only that something
	 * failed ("Failed to instantiate bridge") leaves what failed to its cause.
	 */
	private static String reasons(Throwable e) {
		StringBuilder reasons = new StringBuilder();
		for (Throwable at = e; at != null; at = at.getCause() == at ? null : at.getCause()) {
			String message = at.getMessage();
			if (message != null && reasons.indexOf(message) < 0) {
				reasons.append(reasons.isEmpty() ? "" : ": ").append(message);
			}
		}
		return reasons.toString();
	}

	/**
	 * A cheap pre-check, for a caller that would otherwise do expensive work before {@link #register}
	 * refuses it. It is not the guarantee: {@code register} states that atomically, since between this and
	 * the put another thread may have claimed the key.
	 */
	private <T> void checkExists(Type targetClass) throws DataBindException {
		if (descriptors.containsKey(targetClass)) {
			throw new DataBindException(String.format("Class already registered: %s", targetClass.getTypeName()));
		}
	}

	/**
	 * Claims {@code targetClass} for {@code descriptor}, or refuses because something else already holds it.
	 *
	 * <p><b>{@code putIfAbsent} rather than a check and a put</b>, which is the same guarantee stated
	 * atomically. Under the split form two threads registering one class both passed the check and the
	 * second silently overwrote the first -- so a duplicate registration that is an error on one thread
	 * became a silent replacement on two, and whichever descriptor lost had already been handed out by
	 * {@link #getDescriptor}. It is the third instance of the shape {@code TsonSchemaRegistry.register} and
	 * {@code getDescriptor} were already fixed for: check the map, do the work, then write without looking
	 * again.
	 */
	private <T> void register(Type targetClass, DataClass descriptor) throws DataBindException {
		if (descriptors.putIfAbsent(targetClass, descriptor) != null) {
			throw new DataBindException(String.format("Class already registered: %s", targetClass.getTypeName()));
		}
	}


	/*
	 * Registration is the builder's, not this context's. A context's configuration is fixed once it is
	 * built: descriptors are handed out and cached from the moment the first read runs, so a registration
	 * arriving afterwards either loses a race with a descriptor already given out or silently changes what a
	 * later read produces. Both of these are called only from the constructor, applying the primitives and
	 * then whatever Builder#registerAtom accumulated.
	 */
	/**
	 * The core scalars, registered as themselves: a {@code String} or an {@code int} <em>is</em> a wire
	 * value, so there is nothing to bridge and nothing to analyse -- {@link ClassBinder#atomForm}
	 * would refuse a primitive in any case.
	 */
	private void registerCoreAtom(Class<?> targetClass) throws DataBindException {
		register(targetClass, new DataClassAtom(targetClass));
	}

	/**
	 * {@link Builder#registerAtom(Class)}: the class's <em>own</em> atom form where it declares one, and a
	 * bare {@link DataClassAtom} otherwise.
	 *
	 * <p><b>Analysis first, because an explicit registration must not be worse than none.</b> A class
	 * carrying {@code @Atom}, and a plain enum, already state a bridge to the type the wire carries, and
	 * {@code getDescriptor} would have found it. Writing a bridgeless descriptor straight into the cache
	 * instead settles the class as an atom with no wire form at all -- unreadable and unwritable, and
	 * permanently, since the registration short-circuits analysis for that class.
	 *
	 * <p>The bare form is what remains: a class the <em>encoding</em> already knows as a scalar -- the JDK
	 * types above, and the host types the built-in atom vocabulary reads to ({@code UUID},
	 * {@code LocalDate}, {@code Inet4Address}, …). That is a fact this module cannot check, knowing nothing
	 * of any encoding's vocabulary, so a class that is neither is accepted here and refused by the reader
	 * or writer that meets it.
	 *
	 * <p>A class that declares an atom form <em>badly</em> -- {@code @Atom} on a constructor with no matching
	 * accessor, say -- fails out of here rather than falling back: the author said what they wanted and the
	 * bare form is not it.
	 */
	private void registerAtom(Class<?> targetClass) throws DataBindException {
		DataClassAtom declared = dataClassResolver.atomForm(this, targetClass);
		register(targetClass, declared != null ? declared : new DataClassAtom(targetClass));
	}

	private DataClassAtom registerAtom(Class<?> targetClass, DataBridge<?, ?> bridge) throws DataBindException {
		checkExists(targetClass);
		DataClassAtom dataClass = new DataClassAtom(targetClass, dataClassResolver.atomBridge(targetClass, bridge));
		register(targetClass, dataClass);
		return dataClass;
	}

}
