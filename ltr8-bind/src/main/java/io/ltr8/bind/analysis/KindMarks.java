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
package io.ltr8.bind.analysis;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.analysis.ClassSignals.Signal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What each {@link Kind} reads of a class: the class-level marks it consumes, and the walks over the class's members
 * that refuse a binding annotation it does not read. {@link #judged} applies both, for every kind of either entry
 * point, so a mark is refused unless the kind the entry point gives the class reads it.
 *
 * <p>A record described by its own components reads its members' annotations, and refuses what none of its components
 * applies ({@link ComponentAnnotations#refuseUnapplied}) where its finders have found them; no walk here can.
 */
final class KindMarks {

	/** How a class comes to be described, each with its own reading of the kinds it gives. */
	enum Entry {
		/** {@link ClassBinder#resolve}, by {@link Kind#of}. */
		RESOLVE,
		/** {@link ClassBinder#atomForm}, by {@link Kind#asAtom}. */
		REGISTER_ATOM
	}

	/** A walk over a class's members, refusing a binding annotation on one its kind does not read. */
	enum Walk {
		/** {@link ComponentAnnotations#refuseAny}. */
		ANY,
		/** {@link ComponentAnnotations#refuseUnread}. */
		UNREAD,
		/** {@link ComponentAnnotations#refuseAtom}, which judges every {@code @Atom} mark. */
		ATOM,
		/** {@link ComponentAnnotations#refuseInheritedWayIn}. */
		INHERITED_WAY_IN,
		/** {@link Kind#RECORD}'s walks, over the record class a bridged class names, which describes it. */
		BRIDGED_RECORD
	}

	/**
	 * What a kind reads: the {@link #MARKS} it consumes, the JDK facts it needs and those it cannot describe, and its
	 * walks. A kind running {@link Walk#ATOM} judges the {@code @Atom} marks there.
	 */
	private record Reading(Set<Signal> marks, Set<Signal> needs, Set<Signal> excludes, List<Walk> walks) {
	}

	/** The signals an author writes on a class, rather than facts the JDK reports of it. */
	private static final Set<Signal> MARKS = EnumSet.of(Signal.TRANSPARENT, Signal.TUPLE, Signal.RECORD_ON_TYPE,
			Signal.RECORD_ON_CONSTRUCTOR, Signal.RECORD_ON_HIDDEN_CONSTRUCTOR, Signal.UNION, Signal.ATOM_ON_TYPE,
			Signal.ATOM_ON_CONSTRUCTOR,
			Signal.ATOM_ON_STATIC_METHOD, Signal.ATOM_ON_INSTANCE_METHOD, Signal.TO_DATA, Signal.FIELD_ORDER);

	private static final Set<Signal> ATOM_SIGNALS = EnumSet.of(Signal.ATOM_ON_TYPE, Signal.ATOM_ON_CONSTRUCTOR,
			Signal.ATOM_ON_STATIC_METHOD, Signal.ATOM_ON_INSTANCE_METHOD);

	private static final Map<Kind, Reading> RESOLVED = new EnumMap<>(Kind.class);

	private static final Map<Kind, Reading> REGISTERED = new EnumMap<>(Kind.class);

	static {
		// No kind reads @Record on a constructor nothing can build the class through.
		Set<Signal> all = but(MARKS, Signal.RECORD_ON_HIDDEN_CONSTRUCTOR);
		RESOLVED.put(Kind.ANNOTATED, reading(all, Set.of()));
		RESOLVED.put(Kind.TRANSPARENT, reading(but(all, Signal.TUPLE, Signal.RECORD_ON_TYPE,
				Signal.RECORD_ON_CONSTRUCTOR, Signal.UNION, Signal.TO_DATA, Signal.FIELD_ORDER),
				Set.of(Signal.JDK_RECORD), Walk.ANY));
		RESOLVED.put(Kind.TUPLE, reading(but(all, Signal.RECORD_ON_TYPE, Signal.RECORD_ON_CONSTRUCTOR, Signal.UNION,
				Signal.TO_DATA, Signal.FIELD_ORDER), Set.of(Signal.JDK_RECORD), Walk.ANY));
		RESOLVED.put(Kind.RECORD, reading(but(all, Signal.UNION), Set.of(), Set.of(Signal.INTERFACE, Signal.ABSTRACT),
				Walk.INHERITED_WAY_IN));
		RESOLVED.put(Kind.BRIDGED, reading(but(all, Signal.RECORD_ON_TYPE, Signal.RECORD_ON_CONSTRUCTOR, Signal.UNION,
				Signal.FIELD_ORDER), Set.of(), Walk.UNREAD, Walk.ATOM, Walk.BRIDGED_RECORD));
		RESOLVED.put(Kind.ENUM, reading(but(all, Signal.RECORD_ON_TYPE, Signal.RECORD_ON_CONSTRUCTOR, Signal.UNION,
				Signal.TO_DATA), Set.of(), Walk.UNREAD, Walk.ATOM));
		RESOLVED.put(Kind.UNION, reading(all, Set.of(), Set.of(Signal.MAP, Signal.COLLECTION), Walk.UNREAD,
				Walk.ATOM));
		RESOLVED.put(Kind.ATOM, reading(but(all, Signal.UNION), Set.of(), Walk.UNREAD, Walk.INHERITED_WAY_IN));
		RESOLVED.put(Kind.MAP, reading(but(all, Signal.UNION), Set.of(), Walk.UNREAD, Walk.ATOM));
		RESOLVED.put(Kind.ARRAY, reading(but(all, Signal.UNION), Set.of(), Walk.UNREAD, Walk.ATOM));
		RESOLVED.put(Kind.NONE, reading(but(all, Signal.UNION), Set.of()));

		REGISTERED.put(Kind.ENUM, RESOLVED.get(Kind.ENUM));
		// An interface or abstract class registered as an atom is a union where it is described.
		REGISTERED.put(Kind.ATOM, reading(all, Set.of(), Walk.UNREAD, Walk.INHERITED_WAY_IN));
		REGISTERED.put(Kind.NONE, reading(all, Set.of()));
		if (!RESOLVED.keySet().containsAll(EnumSet.allOf(Kind.class))) {
			throw new IllegalStateException("A kind has no reading: " + RESOLVED.keySet());
		}
	}

	private KindMarks() {
	}

	private static Reading reading(Set<Signal> marks, Set<Signal> needs, Walk... walks) {
		return reading(marks, needs, Set.of(), walks);
	}

	private static Reading reading(Set<Signal> marks, Set<Signal> needs, Set<Signal> excludes, Walk... walks) {
		return new Reading(Set.copyOf(marks), needs, excludes, List.of(walks));
	}

	private static Set<Signal> but(Set<Signal> marks, Signal... unread) {
		Set<Signal> read = EnumSet.copyOf(marks);
		read.removeAll(List.of(unread));
		return read;
	}

	interface Describing<D extends DataClass> {
		D describe() throws DataBindException;
	}

	/**
	 * What {@code describing} describes {@code signals}' class as, once its marks and members are judged against
	 * {@code kind}'s reading in {@code entry}. Its marks are judged before it is described, since a mark no kind
	 * reads is the fault to name. A record's and an atom's members are walked after: a record's binder judges its
	 * constructor first, and how an atom is written names the value its way in takes.
	 */
	static <D extends DataClass> D judged(Entry entry, Kind kind, ClassSignals signals, Describing<D> describing)
			throws DataBindException {
		Reading reading = (entry == Entry.RESOLVE ? RESOLVED : REGISTERED).get(kind);
		if (reading == null) {
			throw new IllegalStateException(entry + " gives no class the kind " + kind);
		}
		for (Signal need : reading.needs()) {
			if (!signals.has(need)) {
				throw unmet(kind, signals);
			}
		}
		for (Signal excluded : reading.excludes()) {
			if (signals.has(excluded)) {
				throw excluded(kind, signals);
			}
		}
		Class<?> bridgedTo = kind == Kind.BRIDGED ? ToDataBridges.findToDataClass(signals.type()) : null;
		String written = written(kind, signals, null, bridgedTo);
		// Whichever entry point describes it, nothing makes a concrete class a union by its mark.
		if (signals.has(Signal.UNION) && !signals.has(Signal.ABSTRACT) && !signals.has(Signal.SEALED)) {
			throw unread(kind, signals, Signal.UNION, written, bridgedTo);
		}
		for (Signal mark : MARKS) {
			if (signals.has(mark) && !reading.marks().contains(mark)
					&& !(ATOM_SIGNALS.contains(mark) && reading.walks().contains(Walk.ATOM))) {
				throw unread(kind, signals, mark, written, bridgedTo);
			}
		}
		Union union = signals.union();
		if (kind == Kind.UNION && signals.has(Signal.SEALED) && union != null
				&& (union.value().length > 0 || !union.sealed())) {
			throw new CodeAnalysisException(String.format("%s is sealed, so its members are the classes it permits, but "
					+ "carries %s: the members come from permits, so remove its attributes", signals.type().getName(),
					ComponentAnnotations.show(union)));
		}
		boolean walkedAfter = kind == Kind.RECORD || kind == Kind.ATOM;
		if (!walkedAfter) {
			walk(reading, signals.type(), written, bridgedTo);
		}
		D described = describing.describe();
		if (walkedAfter && described != null) {
			walk(reading, signals.type(), written(kind, signals, described, bridgedTo), bridgedTo);
		}
		return described;
	}

	private static void walk(Reading reading, Class<?> type, String written, Class<?> bridgedTo)
			throws DataBindException {
		for (Walk walk : reading.walks()) {
			walk(walk, type, written, bridgedTo);
		}
	}

	private static void walk(Walk walk, Class<?> type, String written, Class<?> bridgedTo)
			throws DataBindException {
		switch (walk) {
			case ANY -> ComponentAnnotations.refuseAny(type, written);
			case UNREAD -> ComponentAnnotations.refuseUnread(type, written);
			case ATOM -> ComponentAnnotations.refuseAtom(type, written);
			case INHERITED_WAY_IN -> ComponentAnnotations.refuseInheritedWayIn(type, written);
			case BRIDGED_RECORD -> {
				for (Walk recordWalk : RESOLVED.get(Kind.RECORD).walks()) {
					walk(recordWalk, bridgedTo, written(Kind.RECORD, ClassSignals.of(bridgedTo), null, null), null);
				}
			}
		}
	}

	/** How {@code kind} writes {@code signals}' class, completing "{@code type} ...". */
	private static String written(Kind kind, ClassSignals signals, DataClass described, Class<?> bridgedTo) {
		return switch (kind) {
			case TRANSPARENT -> "is @Transparent, written as its one component";
			case TUPLE -> "is a @Tuple, written as its components by position";
			case RECORD -> "is a record, written as its components";
			case BRIDGED -> "is bridged through ToData to " + bridgedTo.getName() + ", which describes it";
			case ENUM -> "is an enum, written as its constant's name";
			case UNION -> "is a union, written as one of its members";
			case ATOM -> described == null ? "is an atom, written as the value its way out returns"
					: "is an atom bridged to " + described.bridge().orElseThrow().dataClass().getName();
			case MAP -> "is a map, written as its entries";
			case ARRAY -> signals.has(Signal.ARRAY) ? "is an array, written as its elements"
					: "is a collection, written as its elements";
			case ANNOTATED, NONE -> "is described as nothing";
		};
	}

	private static DataBindException unmet(Kind kind, ClassSignals signals) {
		Class<?> type = signals.type();
		return switch (kind) {
			case TRANSPARENT -> new DataBindException(String.format("@Transparent is only meaningful on a record: %s "
					+ "is not one, so there is no single component to be transparent to", type.getName()));
			case TUPLE -> new CodeAnalysisException("@Tuple can only be applied to a genuine Java record: " + type);
			default -> throw new IllegalStateException(kind + " needs no fact " + type.getName() + " lacks");
		};
	}

	private static DataBindException excluded(Kind kind, ClassSignals signals) {
		Class<?> type = signals.type();
		if (kind == Kind.UNION) {
			return container(signals);
		}
		if (kind != Kind.RECORD) {
			throw new IllegalStateException(kind + " excludes no fact " + type.getName() + " has");
		}
		String record = label(ownWay(kind, signals), type);
		String noun = signals.has(Signal.INTERFACE) ? "interface" : "abstract class";
		String built = "which describes a class built through its constructor: ";
		if (signals.has(Signal.SEALED)) {
			return new CodeAnalysisException(String.format("%s is a sealed %s, a union of the classes it permits, but "
					+ "carries %s, %sremove it", type.getName(), noun, record, built));
		}
		if (signals.has(Signal.UNION)) {
			return new CodeAnalysisException(String.format("%s is an %s carrying @Union, a union of its members, but "
					+ "carries %s, %sremove it", type.getName(), noun, record, built));
		}
		return new CodeAnalysisException(String.format("%s is an %s, which nothing can build, but carries %s, %s%s",
				type.getName(), noun, record, built, signals.has(Signal.INTERFACE) ? "remove it"
						: "make " + type.getSimpleName() + " concrete, or remove it"));
	}

	private static DataBindException unread(Kind kind, ClassSignals signals, Signal mark, String written,
			Class<?> bridgedTo) {
		Class<?> type = signals.type();
		Signal own = ownWay(kind, signals);
		if (own != null && WAYS.containsKey(mark)) {
			return new CodeAnalysisException(String.format("%s says two ways to write it, %s, %s, and %s, %s: remove one "
					+ "of them", type.getName(), label(own, type), WAYS.get(own), label(mark, type), WAYS.get(mark)));
		}
		if (mark == Signal.UNION) {
			if (signals.has(Signal.MAP) || signals.has(Signal.COLLECTION)) {
				return container(signals);
			}
			if (kind == Kind.BRIDGED && signals.has(Signal.ABSTRACT)) {
				return new CodeAnalysisException(String.format("%s carries @Union, a union of its members, but "
						+ "implements ToData, bridged to the record class it names: remove one of them", type.getName()));
			}
			return new CodeAnalysisException(String.format("%s carries @Union, but %s; @Union belongs on an interface "
					+ "or abstract class, written as one of its members: remove it", type.getName(),
					kind == Kind.NONE ? "is a concrete class" : written));
		}
		if (mark == Signal.FIELD_ORDER) {
			return switch (kind) {
				case TRANSPARENT -> new DataBindException(type.getName() + " carries @FieldOrder, but is @Transparent, "
						+ "written as its one component; an order applies only to a class described by its own "
						+ "components");
				case TUPLE -> new CodeAnalysisException(type.getName() + " carries @FieldOrder, but is a @Tuple, whose "
						+ "elements are its record components in declared order; an order applies only to a class "
						+ "described by its own components");
				default -> new DataBindException(String.format("%s carries @FieldOrder, but %s; an order applies only "
						+ "to a class described by its own components, so it belongs on %s", type.getName(), written,
						bridgedTo.getName()));
			};
		}
		if (mark == Signal.TO_DATA && kind == Kind.ENUM) {
			return new CodeAnalysisException(String.format("%s %s, but implements ToData, which nothing reads: remove "
					+ "it; an enum written another way is registered with its bridge, registerAtom(%s.class, bridge)",
					type.getName(), written, type.getSimpleName()));
		}
		if (mark == Signal.RECORD_ON_HIDDEN_CONSTRUCTOR && kind != Kind.ENUM) {
			return new CodeAnalysisException(String.format("%s marks %s @Record, but it is not public, so nothing "
					+ "builds %s through it: make it public, or remove @Record", type.getName(),
					constructor(type, Record.class, false), type.getSimpleName()));
		}
		return new CodeAnalysisException(String.format("%s %s, so nothing reads %s: remove it", type.getName(), written,
				label(mark, type)));
	}

	/** Refuses a sealed or {@code @Union} type that is a map or a collection too. */
	private static DataBindException container(ClassSignals signals) {
		Class<?> type = signals.type();
		boolean map = signals.has(Signal.MAP);
		String container = map ? "java.util.Map" : "java.util.Collection";
		String marked = signals.has(Signal.SEALED) ? "is sealed and a " : "carries @Union and is a ";
		String fix = signals.has(Signal.SEALED) ? "unseal it" : "remove @Union";
		return new CodeAnalysisException(String.format("%s %s%s, so it could mean a union, written as one of its "
				+ "members, or %s: %s, or extend no %s", type.getName(), marked, container,
				map ? "a map, written as its entries" : "a collection, written as its elements", fix, container));
	}

	/** How each mark that says how a record is written says it, completing "{@code type} is written ...". */
	private static final Map<Signal, String> WAYS = Map.of(Signal.TRANSPARENT, "as its one component", Signal.TUPLE,
			"as its components by position", Signal.RECORD_ON_TYPE, "as its components by name",
			Signal.RECORD_ON_CONSTRUCTOR, "as its components by name", Signal.TO_DATA,
			"as the record class it names");

	/** The mark that gives {@code signals}' class {@code kind}, where one of the {@link #WAYS} does. */
	private static Signal ownWay(Kind kind, ClassSignals signals) {
		return switch (kind) {
			case TRANSPARENT -> Signal.TRANSPARENT;
			case TUPLE -> Signal.TUPLE;
			case BRIDGED -> Signal.TO_DATA;
			case RECORD -> signals.has(Signal.RECORD_ON_TYPE) ? Signal.RECORD_ON_TYPE
					: signals.has(Signal.RECORD_ON_CONSTRUCTOR) ? Signal.RECORD_ON_CONSTRUCTOR : null;
			default -> null;
		};
	}

	private static String label(Signal mark, Class<?> type) {
		return switch (mark) {
			case TRANSPARENT -> "@Transparent";
			case TUPLE -> "@Tuple";
			case RECORD_ON_TYPE -> "@Record";
			case RECORD_ON_CONSTRUCTOR -> "@Record on " + constructor(type, Record.class, true);
			case RECORD_ON_HIDDEN_CONSTRUCTOR -> "@Record on " + constructor(type, Record.class, false);
			case TO_DATA -> "ToData";
			case UNION -> "@Union";
			case FIELD_ORDER -> "@FieldOrder";
			case ATOM_ON_TYPE, ATOM_ON_CONSTRUCTOR, ATOM_ON_STATIC_METHOD, ATOM_ON_INSTANCE_METHOD -> "@Atom";
			default -> throw new IllegalStateException(mark + " is no mark");
		};
	}

	/** The constructor of {@code type}, public or not, marked {@code annotation}, as {@code Name(Type, Type)}. */
	private static String constructor(Class<?> type, Class<? extends Annotation> annotation, boolean isPublic) {
		for (Constructor<?> constructor : type.getDeclaredConstructors()) {
			if (constructor.isAnnotationPresent(annotation)
					&& Modifier.isPublic(constructor.getModifiers()) == isPublic) {
				return "the constructor " + type.getSimpleName() + Stream.of(constructor.getParameterTypes())
						.map(Class::getSimpleName).collect(Collectors.joining(", ", "(", ")"));
			}
		}
		throw new IllegalStateException(type.getName() + " marks no constructor " + annotation.getSimpleName());
	}
}
