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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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

	// --- a @Union constructor parameter the constructor analysis cannot match to a field

	/** Its {@code @Union} parameter reaches its field through a call, and no {@code @Field} names it. */
	public static class UnionThroughCall {

		private final Object tag;

		@Record
		public UnionThroughCall(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = Objects.requireNonNull(tag);
		}

		public Object tag() {
			return tag;
		}
	}

	/** As {@link UnionThroughCall}, with {@code @Field} naming the parameter: described. */
	public static class NamedUnionThroughCall {

		private final Object tag;

		@Record
		public NamedUnionThroughCall(@Field("tag") @Union({ String.class, Integer.class }) Object tag) {
			this.tag = Objects.requireNonNull(tag);
		}

		public Object tag() {
			return tag;
		}
	}

	/** Assigns its {@code @Union} parameter to its field unchanged, with no {@code @Field}: described. */
	public static class UnionAssignedUnchanged {

		private final Object tag;

		@Record
		public UnionAssignedUnchanged(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}

		public Object tag() {
			return tag;
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

	/** A three-class cycle, {@code RingFirst -> RingMiddle -> RingLast -> RingFirst}, refused in the middle. */
	public record RingFirst(String tag, RingMiddle middle) {
	}

	public record RingMiddle(RingLast last, ProtectedConstructor value) {
	}

	public record RingLast(String tag, Optional<RingFirst> first) {
	}

	/**
	 * A cycle nested in a larger one: {@code LatticeLoop} closes on {@code LatticeMid}, {@code LatticeUp} on {@code
	 * LatticeTop}, and {@code LatticeSide}, met after {@code LatticeMid} is described, holds {@code LatticeLoop}, which
	 * reaches the refused {@code LatticeTop} only through {@code LatticeMid}.
	 */
	public record LatticeTop(LatticeMid mid, LatticeSide side, ProtectedConstructor value) {
	}

	public record LatticeMid(LatticeLoop loop, LatticeUp up) {
	}

	public record LatticeLoop(Optional<LatticeMid> mid) {
	}

	public record LatticeUp(Optional<LatticeTop> top) {
	}

	public record LatticeSide(LatticeLoop loop) {
	}

	/** A good cycle of its own. */
	public record GoodLoop(String tag, Optional<GoodLoop> next) {
	}

	/** A refused cycle holding a good cycle that does not reach it. */
	public record RefusedLoopHoldingGoodLoop(GoodLoop loop, Optional<RefusedLoopHoldingGoodLoop> next,
			ProtectedConstructor value) {
	}

	/** Counts its instances: a {@code @Field} bridge is built each time the field holding it is described. */
	public static class CountingBridge implements DataBridge<String, String> {

		public static final AtomicInteger BUILT = new AtomicInteger();

		public CountingBridge() {
			BUILT.incrementAndGet();
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

	public record Counted(@Field(bridge = CountingBridge.class) String value) {
	}

	/** A refused cycle holding the good {@link Counted}, described before the refusal is met. */
	public record RefusedLoopHoldingCounted(Counted counted, Optional<RefusedLoopHoldingCounted> next,
			ProtectedConstructor value) {
	}

	/** Refused, after a field whose bridge is built while it is being described. */
	public record RefusedAfterCountedField(@Field(bridge = CountingBridge.class) String value,
			ProtectedConstructor refused) {
	}

	// --- refused over the super class constructor a hand-written immutable class calls

	public static class ProtectedSuperBase {

		private final String name;

		protected ProtectedSuperBase(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class CallsProtectedSuper extends ProtectedSuperBase {

		@Record
		public CallsProtectedSuper(String name) {
			super(name);
		}
	}

	public static class PackagePrivateSuperBase {

		private final String name;

		PackagePrivateSuperBase(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class CallsPackagePrivateSuper extends PackagePrivateSuperBase {

		@Record
		public CallsPackagePrivateSuper(String name) {
			super(name);
		}
	}

	/** Its own super constructor is public; the one beneath that is not. */
	public static class ReachesProtectedSuper extends CallsProtectedSuper {

		@Record
		public ReachesProtectedSuper(String name) {
			super(name);
		}
	}

	public static class TransformingSuperBase {

		private final String name;

		public TransformingSuperBase(String name) {
			this.name = name.trim();
		}

		public String name() {
			return name;
		}
	}

	public static class CallsTransformingSuper extends TransformingSuperBase {

		@Record
		public CallsTransformingSuper(String name) {
			super(name);
		}
	}

	public static class DroppingSuperBase {

		private final String name;

		public DroppingSuperBase(String name, String ignored) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	public static class CallsDroppingSuper extends DroppingSuperBase {

		@Record
		public CallsDroppingSuper(String name, String ignored) {
			super(name, ignored);
		}
	}

	public static class InheritedFieldBase {

		protected String name;

		public String name() {
			return name;
		}
	}

	/** Assigns a field its super class declares, so the field is not among its own. */
	public static class AssignsInheritedField extends InheritedFieldBase {

		@Record
		public AssignsInheritedField(String name) {
			this.name = name;
		}
	}

	public record HoldsCallsProtectedSuper(String name, CallsProtectedSuper value) {
	}

	public record HoldsCallsTransformingSuper(String name, CallsTransformingSuper value) {
	}

	public record HoldsAssignsInheritedField(String name, AssignsInheritedField value) {
	}

	public record HoldsReachesProtectedSuper(String name, List<ReachesProtectedSuper> values) {
	}
}
