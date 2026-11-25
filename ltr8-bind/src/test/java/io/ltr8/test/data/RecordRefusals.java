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

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.FieldOrder;
import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Union;
import io.ltr8.test.data.ToDataBridges.ProtectedConstructor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Classes the record binder refuses to describe, one fault each, and good records holding them.
 */
public final class RecordRefusals {

	private RecordRefusals() {
	}

	// --- refused by the record binder itself

	public record TwoCarriers(String name, Annotations first, Annotations second) {
	}

	public record EmptyUnion(@Union Object value) {
	}

	public record UnassignableUnion(@Union({ String.class }) Integer value) {
	}

	@FieldOrder({ "a" })
	public record OrderCountMismatch(String a, String b) {
	}

	@FieldOrder({ "a", "c" })
	public record OrderNamesNoField(String a, String b) {
	}

	@Record
	public static class NoDesignatedConstructor {

		public NoDesignatedConstructor(String text) {
		}

		public NoDesignatedConstructor(int number) {
		}
	}

	public record OnlyOtherProfiles(String a) {

		@Profile("v1")
		public OnlyOtherProfiles {
		}
	}

	// --- refused over a component's @Field bridge

	public static class GenericBridge<T> implements DataBridge<T, String> {

		@Override
		public T toData(String b) {
			return null;
		}

		@Override
		public String toObject(T s) {
			return null;
		}
	}

	public static class StringToIntegerBridge implements DataBridge<String, Integer> {

		@Override
		public String toData(Integer b) {
			return String.valueOf(b);
		}

		@Override
		public Integer toObject(String s) {
			return Integer.valueOf(s);
		}
	}

	public static class ThrowingBridge implements DataBridge<String, String> {

		public ThrowingBridge() {
			throw new IllegalStateException("this bridge will not be built");
		}

		@Override
		public String toData(String b) {
			return b;
		}

		@Override
		public String toObject(String s) {
			return s;
		}
	}

	public static class BridgeWithoutNoArgumentConstructor implements DataBridge<String, String> {

		public BridgeWithoutNoArgumentConstructor(String prefix) {
		}

		@Override
		public String toData(String b) {
			return b;
		}

		@Override
		public String toObject(String s) {
			return s;
		}
	}

	public record BridgeTypeVariable(@Field(bridge = GenericBridge.class) String value) {
	}

	public record BridgeObjectMismatch(@Field(bridge = StringToIntegerBridge.class) Long value) {
	}

	public record BridgeConstructorThrows(@Field(bridge = ThrowingBridge.class) String value) {
	}

	public record BridgeNeedsArgument(@Field(bridge = BridgeWithoutNoArgumentConstructor.class) String value) {
	}

	// --- refused by a component finder

	public record ProfileFieldCountMismatch(String a, String b) {

		@Profile(value = "v1", fields = { "a", "b" })
		public ProfileFieldCountMismatch(String a) {
			this(a, null);
		}
	}

	public record ProfileNamesNoComponent(String a, String b) {

		@Profile(value = "v1", fields = { "z" })
		public ProfileNamesNoComponent(String a) {
			this(a, null);
		}
	}

	public record ProfileParameterUnnamed(String a, String b) {

		@Profile("v1")
		public ProfileParameterUnnamed(String a) {
			this(a, null);
		}
	}

	public static class ConstructorTransformsArgument {

		private final String name;

		@Record
		public ConstructorTransformsArgument(String name) {
			this.name = name.trim();
		}

		public String name() {
			return name;
		}
	}

	public static class NoAccessorForArgument {

		@SuppressWarnings("unused")
		private final String name;

		@Record
		public NoAccessorForArgument(String name) {
			this.name = name;
		}
	}

	public static class PrivateAccessor {

		private final String name;

		@Record
		public PrivateAccessor(String name) {
			this.name = name;
		}

		@SuppressWarnings("unused")
		private String name() {
			return name;
		}
	}

	public static class PrivateGetterSetter {

		private String value;

		@Record
		public PrivateGetterSetter() {
		}

		@SuppressWarnings("unused")
		private String getValue() {
			return value;
		}

		@SuppressWarnings("unused")
		private void setValue(String value) {
			this.value = value;
		}
	}

	public static class FieldAnnotationWithoutAccessors {

		@Field("renamed")
		@SuppressWarnings("unused")
		private String hidden;

		@Record
		public FieldAnnotationWithoutAccessors() {
		}
	}

	public static class GetterAndSetterRenamedApart {

		private String value;

		@Record
		public GetterAndSetterRenamedApart() {
		}

		@Field("fromGetter")
		public String getValue() {
			return value;
		}

		@Field("fromSetter")
		public void setValue(String value) {
			this.value = value;
		}
	}

	// --- refused by the descriptor of a component's type

	public static class Unconvertible {
	}

	@SuppressWarnings("rawtypes")
	public record RawList(List items) {
	}

	public record WildcardList(List<? extends CharSequence> items) {
	}

	@SuppressWarnings("rawtypes")
	public record RawMap(Map entries) {
	}

	// --- good records holding a refused type

	public record HoldsUnconvertible(String name, Unconvertible value) {
	}

	public record HoldsRefused(String name, ProtectedConstructor value) {
	}

	public record HoldsRefusedList(String name, List<ProtectedConstructor> values) {
	}

	public record HoldsRefusedOptional(String name, Optional<ProtectedConstructor> value) {
	}

	public record HoldsRefusedMap(String name, Map<String, ProtectedConstructor> values) {
	}

	public record HoldsRefusedArray(String name, ProtectedConstructor[] values) {
	}

	public record HoldsHolder(String name, HoldsRefused holder) {
	}

	public record HoldsTwoDeep(String name, HoldsHolder holder) {
	}

	public record HoldsTwoCarriers(String name, TwoCarriers value) {
	}

	public record HoldsBridgeNeedsArgument(String name, BridgeNeedsArgument value) {
	}

	public record Good(String name, int count) {
	}

	// --- a cyclic type graph with a refused member

	/** Reaches itself through an Optional, and holds a refused type beside it. */
	public record LoopWithRefused(Optional<LoopWithRefused> next, ProtectedConstructor value) {
	}

	/** On a cycle through {@link CycleMember}, and holding a refused type after the cycle's member. */
	public record CycleTop(CycleMember member, ProtectedConstructor value) {
	}

	/** Good on its own terms, but reaches the refused {@link CycleTop}. */
	public record CycleMember(String tag, Optional<CycleTop> top) {
	}
}
