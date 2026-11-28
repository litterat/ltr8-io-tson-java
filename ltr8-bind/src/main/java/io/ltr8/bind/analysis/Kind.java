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

import io.ltr8.bind.analysis.ClassSignals.Signal;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** What {@link ClassBinder} describes a class as, decided by {@link #of} from its {@link ClassSignals}. */
enum Kind {
	/** {@link io.ltr8.annotation.Annotated} itself: a value boxed with its own annotations. */
	ANNOTATED,
	TRANSPARENT,
	TUPLE,
	/** Described by its own components. */
	RECORD,
	/** Bridged through {@code ToData} to the record class it names. */
	BRIDGED,
	ENUM,
	UNION,
	ATOM,
	MAP,
	/** An array or a collection. */
	ARRAY,
	/** Nothing the class carries says how to bind it. */
	NONE;

	/**
	 * The kind {@code signals} decide: the first of these that applies.
	 *
	 * <ol>
	 * <li>{@link #ANNOTATED}. {@code Annotated} is a record, and as one its annotations would be an authored field
	 *     rather than the framing they are.
	 * <li>{@link #TRANSPARENT}, then {@link #TUPLE}: each says how a record is written, so each is asked before the
	 *     record it marks is taken as one.
	 * <li>{@link #RECORD}: a JDK record, or {@code @Record} on the type or a public constructor; {@link #BRIDGED}
	 *     where it implements {@code ToData}.
	 * <li>{@link #BRIDGED}: any other class implementing {@code ToData} and marking no {@code @Atom} on itself or a
	 *     way in. One that marks one is an atom, and {@code ToData} at most its way out.
	 * <li>{@link #ENUM}, which needs no mark: {@code isEnum} is as plain a JDK fact as {@code isRecord}. Asked ahead
	 *     of a union, since the JDK seals an enum with a constant body.
	 * <li>{@link #UNION}: sealed, or abstract and marked {@code @Union}; never a collection.
	 * <li>{@link #ATOM}: a primitive, or {@code @Atom} on the type, a constructor or a static method. An instance
	 *     method marked {@code @Atom} is a way out, which a class of any kind may inherit, so it decides nothing.
	 * <li>{@link #MAP}, then {@link #ARRAY}.
	 * </ol>
	 */
	static Kind of(Set<Signal> signals) {
		boolean marksAtom = !Collections.disjoint(signals, ClassSignals.ATOM_MARKS);
		if (signals.contains(Signal.ANNOTATED_BOX)) {
			return ANNOTATED;
		}
		if (signals.contains(Signal.TRANSPARENT)) {
			return TRANSPARENT;
		}
		if (signals.contains(Signal.TUPLE)) {
			return TUPLE;
		}
		if (signals.contains(Signal.JDK_RECORD) || signals.contains(Signal.RECORD_ON_TYPE)
				|| signals.contains(Signal.RECORD_ON_CONSTRUCTOR)) {
			return signals.contains(Signal.TO_DATA) ? BRIDGED : RECORD;
		}
		if (signals.contains(Signal.TO_DATA) && !marksAtom) {
			return BRIDGED;
		}
		if (signals.contains(Signal.ENUM)) {
			return ENUM;
		}
		if (!signals.contains(Signal.COLLECTION) && (signals.contains(Signal.SEALED)
				|| signals.contains(Signal.UNION) && signals.contains(Signal.ABSTRACT))) {
			return UNION;
		}
		if (signals.contains(Signal.PRIMITIVE) || marksAtom) {
			return ATOM;
		}
		if (signals.contains(Signal.MAP)) {
			return MAP;
		}
		if (signals.contains(Signal.ARRAY) || signals.contains(Signal.COLLECTION)) {
			return ARRAY;
		}
		return NONE;
	}

	/**
	 * The kind {@code signals} decide for a class registered as an atom: {@link #ENUM}, {@link #ATOM} or
	 * {@link #NONE}. Every signal of another kind is set aside, since registering a record or a union that marks
	 * {@code @Atom} asks for the atom form it states.
	 */
	static Kind asAtom(Set<Signal> signals) {
		Set<Signal> forms = EnumSet.noneOf(Signal.class);
		for (Signal signal : signals) {
			if (signal == Signal.PRIMITIVE || signal == Signal.ENUM || ClassSignals.ATOM_MARKS.contains(signal)) {
				forms.add(signal);
			}
		}
		return of(forms);
	}
}
