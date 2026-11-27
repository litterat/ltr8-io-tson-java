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
import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Tuple;
import io.ltr8.annotation.Unbound;
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

	@FieldOrder({ "a", "a" })
	public record OrderNamesComponentTwice(String a, String b) {
	}

	@FieldOrder({ "y", "z" })
	public record OrderNamesWireName(@Field("z") String first, @Field("y") String second) {
	}

	@FieldOrder({ "name" })
	public record OrderOmitsUnbound(String name, @Unbound int cache) {
	}

	@Tuple
	@FieldOrder({ "b", "a" })
	public record OrderedTuple(String a, int b) {
	}

	@Tuple
	@FieldOrder({ "nothing" })
	public record OrderedTupleNamingNothing(String a, int b) {
	}

	@Transparent
	@FieldOrder({ "value" })
	public record OrderedTransparent(String value) {
	}

	@Transparent
	@FieldOrder({ "nothing" })
	public record OrderedTransparentNamingNothing(String value) {
	}

	@FieldOrder({ "count", "name" })
	public static class OrderedBridged implements ToData<Good> {

		private final Good data;

		public OrderedBridged(Good data) {
			this.data = data;
		}

		@Override
		public Good toData() {
			return data;
		}
	}

	@FieldOrder({ "nothing" })
	public static class OrderedBridgedNamingNothing implements ToData<Good> {

		private final Good data;

		public OrderedBridgedNamingNothing(Good data) {
			this.data = data;
		}

		@Override
		public Good toData() {
			return data;
		}
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

	@SuppressWarnings("rawtypes")
	public static class RawBridge implements DataBridge {

		@Override
		public Object toData(Object b) {
			return b;
		}

		@Override
		public Object toObject(Object s) {
			return s;
		}
	}

	public abstract static class TextBridge<O> implements DataBridge<String, O> {
	}

	@SuppressWarnings("rawtypes")
	public static class RawlyExtendedBridge extends TextBridge {

		@Override
		public String toData(Object b) {
			return String.valueOf(b);
		}

		@Override
		public Object toObject(Object s) {
			return s;
		}
	}

	public static class InheritedOpenBridge<O> extends TextBridge<O> {

		@Override
		public String toData(O b) {
			return String.valueOf(b);
		}

		@Override
		public O toObject(String s) {
			return null;
		}
	}

	public static class NestedOpenBridge<T> implements DataBridge<List<T>, String> {

		@Override
		public List<T> toData(String b) {
			return List.of();
		}

		@Override
		public String toObject(List<T> s) {
			return "";
		}
	}

	public record BridgeTypeVariable(@Field(bridge = GenericBridge.class) String value) {
	}

	public record BridgeRaw(@Field(bridge = RawBridge.class) String value) {
	}

	public record BridgeRawlyExtended(@Field(bridge = RawlyExtendedBridge.class) String value) {
	}

	public record BridgeInheritedOpen(@Field(bridge = InheritedOpenBridge.class) String value) {
	}

	public record BridgeNestedOpen(@Field(bridge = NestedOpenBridge.class) String value) {
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

	public record ProfileNamesComponentTwice(String a, String b, String c) {

		@Profile(value = "v1", fields = { "a", "a" })
		public ProfileNamesComponentTwice(String x, String y) {
			this(x, y, null);
		}
	}

	public record ProfileParametersNameComponentTwice(String a, String b, String c) {

		@Profile("v1")
		public ProfileParametersNameComponentTwice(@Field("a") String x, @Field("a") String y) {
			this(x, y, null);
		}
	}

	public record DesignatedParametersNameComponentTwice(String a, String b, String c) {

		@Record
		public DesignatedParametersNameComponentTwice(@Field("a") String x, @Field("a") String y) {
			this(x, y, null);
		}
	}

	/** Its profile names two components, and {@code @Field} gives the second the first one's name. */
	public record ProfileRenamesOntoAnother(String a, String b, String c) {

		@Profile(value = "v1", fields = { "a", "b" })
		public ProfileRenamesOntoAnother(String x, @Field("a") String y) {
			this(x, y, null);
		}
	}

	public record ProfileParameterUnnamed(String a, String b) {

		@Profile("v1")
		public ProfileParameterUnnamed(String a) {
			this(a, null);
		}
	}

	public record ProfileParameterNarrower(long count, String label) {

		@Profile(value = "v1", fields = { "count" })
		public ProfileParameterNarrower(int count) {
			this(count, null);
		}
	}

	public record ProfileParameterUnboxed(Integer count, String label) {

		@Profile(value = "v1", fields = { "count" })
		public ProfileParameterUnboxed(int count) {
			this(count, null);
		}
	}

	public record ProfileParameterUnrelated(Integer count, String label) {

		@Profile(value = "v1", fields = { "label" })
		public ProfileParameterUnrelated(Integer label) {
			this(label, null);
		}
	}

	public record DesignatedParameterNarrower(long count) {

		@Record
		public DesignatedParameterNarrower(@Field("count") int count) {
			this((long) count);
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

	// --- two components of one wire name, and the same shapes named apart

	/** A public field and a getter/setter pair over another field, both named {@code name}. */
	public static class FieldBesidePair {

		public String name;

		private String label;

		@Record
		public FieldBesidePair() {
		}

		public String getName() {
			return label;
		}

		public void setName(String label) {
			this.label = label;
		}
	}

	/** As {@link FieldBesidePair}, the field named apart by {@code @Field}. */
	public static class FieldBesidePairNamedApart {

		@Field("alias")
		public String name;

		private String label;

		@Record
		public FieldBesidePairNamedApart() {
		}

		public FieldBesidePairNamedApart(String name, String label) {
			this.name = name;
			this.label = label;
		}

		public String getName() {
			return label;
		}

		public void setName(String label) {
			this.label = label;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof FieldBesidePairNamedApart other && Objects.equals(name, other.name)
					&& Objects.equals(label, other.label);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, label);
		}
	}

	/** As {@link FieldBesidePairNamedApart}, ordered: two components of the Java name {@code name}. */
	@FieldOrder({ "alias", "name" })
	public static class OrderedFieldBesidePair {

		@Field("alias")
		public String name;

		private String label;

		@Record
		public OrderedFieldBesidePair() {
		}

		public String getName() {
			return label;
		}

		public void setName(String label) {
			this.label = label;
		}
	}

	/** As {@link PairShadowsBaseComponentNamedApart}, ordered. */
	@FieldOrder({ "name", "own" })
	public static class OrderedPairShadowsBaseComponent extends NamedBase {

		private String own;

		@Record
		public OrderedPairShadowsBaseComponent(String name) {
			super(name);
		}

		@Field("own")
		public String getName() {
			return own;
		}

		public void setName(String own) {
			this.own = own;
		}
	}

	/** A constructor component of its super class's field name, named apart by {@code @Field}, and ordered. */
	@FieldOrder({ "name", "own" })
	public static class OrderedConstructorComponentsOfOneName extends NamedBase {

		private final String name;

		@Record
		public OrderedConstructorComponentsOfOneName(String baseName, String name) {
			super(baseName);
			this.name = name;
		}

		@Field("own")
		public String ownName() {
			return name;
		}
	}

	public static class NamedBase {

		private final String name;

		public NamedBase(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	/** Its own getter/setter pair, over its own field, takes the name of its super class's constructor component. */
	public static class PairShadowsBaseComponent extends NamedBase {

		private String own;

		@Record
		public PairShadowsBaseComponent(String name) {
			super(name);
		}

		public String getName() {
			return own;
		}

		public void setName(String own) {
			this.own = own;
		}
	}

	/** As {@link PairShadowsBaseComponent}, the pair named apart by {@code @Field}. */
	public static class PairShadowsBaseComponentNamedApart extends NamedBase {

		private String own;

		@Record
		public PairShadowsBaseComponentNamedApart(String name) {
			super(name);
		}

		public PairShadowsBaseComponentNamedApart(String name, String own) {
			super(name);
			this.own = own;
		}

		@Field("own")
		public String getName() {
			return own;
		}

		public void setName(String own) {
			this.own = own;
		}

		@Override
		public boolean equals(Object obj) {
			return obj instanceof PairShadowsBaseComponentNamedApart other && Objects.equals(name(), other.name())
					&& Objects.equals(own, other.own);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name(), own);
		}
	}

	/** Two constructor components of one name, which {@code ImmutableFinder} refuses itself. */
	public static class ConstructorComponentsOfOneName extends NamedBase {

		private final String name;

		@Record
		public ConstructorComponentsOfOneName(String baseName, String name) {
			super(baseName);
			this.name = name;
		}

		public String ownName() {
			return name;
		}
	}

	public record ComponentsNamedAlike(@Field("x") String a, @Field("x") String b) {
	}

	public record ComponentsNamedApart(@Field("x") String a, @Field("y") String b) {
	}

	public record UnboundNamedAlike(@Field("x") String a, @Unbound @Field("x") String b) {
	}

	public record CarrierNamedAlike(@Field("annotations") String a, Annotations annotations) {
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

	public record HoldsCallsProtectedSuper(String name, CallsProtectedSuper value) {
	}

	public record HoldsCallsTransformingSuper(String name, CallsTransformingSuper value) {
	}

	public record HoldsReachesProtectedSuper(String name, List<ReachesProtectedSuper> values) {
	}
}
