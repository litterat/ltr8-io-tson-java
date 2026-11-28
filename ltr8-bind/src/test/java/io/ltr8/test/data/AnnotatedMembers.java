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

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Unbound;
import io.ltr8.annotation.Union;
import io.ltr8.test.data.BridgedFields.NumberAsText;
import io.ltr8.test.data.BridgedFields.RecordingNumberAsText;

import java.util.Objects;

/**
 * Components whose {@code @Field}, {@code @Union} or {@code @Unbound} sits on one or more of the members that make
 * them, for each way a class exposes a component.
 */
public final class AnnotatedMembers {

	private AnnotatedMembers() {
	}

	// --- a record bound through its canonical constructor

	/** The header's annotation reaches the field, the parameter and the implicit accessor. */
	public record HeaderField(@Field("a") String x) {
	}

	public record HeaderFieldExplicitAccessor(@Field("a") String x) {

		@Override
		public String x() {
			return x;
		}
	}

	public record AccessorFieldBesideHeader(@Field("a") String x) {

		@Override
		@Field("b")
		public String x() {
			return x;
		}
	}

	public record AccessorRequiredBesideHeader(@Field("x") String x) {

		@Override
		@Field(value = "x", required = true)
		public String x() {
			return x;
		}
	}

	public record AccessorBridgeBesideHeader(@Field Integer x) {

		@Override
		@Field(bridge = NumberAsText.class)
		public Integer x() {
			return x;
		}
	}

	public record ExplicitCanonicalParameterField(String x) {

		public ExplicitCanonicalParameterField(@Field("p") String x) {
			this.x = x;
		}
	}

	public record ExplicitCanonicalParameterBesideAccessor(String x) {

		public ExplicitCanonicalParameterBesideAccessor(@Field("p") String x) {
			this.x = x;
		}

		@Override
		@Field("b")
		public String x() {
			return x;
		}
	}

	public record HeaderValueAccessorRequired(@Field("a") String x) {

		@Override
		@Field(required = true)
		public String x() {
			return x;
		}
	}

	public record HeaderBridgeBesideAccessorBridge(@Field(bridge = NumberAsText.class) Integer x) {

		@Override
		@Field(bridge = RecordingNumberAsText.class)
		public Integer x() {
			return x;
		}
	}

	public record ExplicitCanonicalParameterBridge(Integer x) {

		public ExplicitCanonicalParameterBridge(@Field(bridge = NumberAsText.class) Integer x) {
			this.x = x;
		}
	}

	public record HeaderUnion(@Union({ String.class, Integer.class }) Object tag) {
	}

	public record HeaderUnionExplicitAccessor(@Union({ String.class, Integer.class }) Object tag) {

		@Override
		public Object tag() {
			return tag;
		}
	}

	public record AccessorUnionBesideHeader(@Union({ String.class, Integer.class }) Object tag) {

		@Override
		@Union({ String.class })
		public Object tag() {
			return tag;
		}
	}

	public record ExplicitCanonicalParameterUnion(Object tag) {

		public ExplicitCanonicalParameterUnion(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}
	}

	public record UnboundAccessor(String name, int cache) {

		@Override
		@Unbound
		public int cache() {
			return cache;
		}
	}

	public record UnboundExplicitCanonicalParameter(String name, int cache) {

		public UnboundExplicitCanonicalParameter(String name, @Unbound int cache) {
			this.name = name;
			this.cache = cache;
		}
	}

	public record UnboundHeader(String name, @Unbound int cache) {
	}

	// --- a record bound through a @Profile constructor taking a subset of its components

	public record ProfiledParameterBesideHeader(@Field("a") String x, String y) {

		@Profile(value = "p", fields = { "x" })
		public ProfiledParameterBesideHeader(@Field("b") String x) {
			this(x, null);
		}
	}

	/** The record's own members disagree on the name, which the profile's parameter states. */
	public record ProfiledParameterOverDisagreement(@Field("a") String x, String y) {

		@Profile(value = "p", fields = { "x" })
		public ProfiledParameterOverDisagreement(@Field("b") String x) {
			this(x, null);
		}

		@Override
		@Field("c")
		public String x() {
			return x;
		}
	}

	/** The record's own members disagree on the name, which the profile's parameter leaves unstated. */
	public record ProfiledSilentParameterBesideDisagreement(@Field("a") String x, String y) {

		@Profile(value = "p", fields = { "x" })
		public ProfiledSilentParameterBesideDisagreement(@Field(required = true) String x) {
			this(x, null);
		}

		@Override
		@Field("c")
		public String x() {
			return x;
		}
	}

	public record ProfiledParameterRequiredBesideHeaderName(@Field("a") String x, String y) {

		@Profile(value = "p", fields = { "x" })
		public ProfiledParameterRequiredBesideHeaderName(@Field(required = true) String x) {
			this(x, null);
		}
	}

	public record ProfiledHeaderBridge(@Field(bridge = NumberAsText.class) Integer x, String y) {

		@Profile(value = "p", fields = { "x" })
		public ProfiledHeaderBridge(Integer x) {
			this(x, null);
		}
	}

	public record ProfiledParameterUnionBesideHeader(@Union({ String.class }) Object tag, String y) {

		@Profile(value = "p", fields = { "tag" })
		public ProfiledParameterUnionBesideHeader(@Union({ String.class, Integer.class }) Object tag) {
			this(tag, null);
		}
	}

	public record ProfiledSilentParameterBesideUnionDisagreement(@Union({ String.class, Integer.class }) Object tag,
			String y) {

		@Profile(value = "p", fields = { "tag" })
		public ProfiledSilentParameterBesideUnionDisagreement(Object tag) {
			this(tag, null);
		}

		@Override
		@Union({ String.class })
		public Object tag() {
			return tag;
		}
	}

	public record ProfiledFieldsBesideParameterField(String x, String y) {

		@Profile(value = "p", fields = { "x" })
		public ProfiledFieldsBesideParameterField(@Field("b") String x) {
			this(x, null);
		}
	}

	public record ProfiledParameterField(String x, String y) {

		@Profile("p")
		public ProfiledParameterField(@Field(value = "x", required = true) String x) {
			this(x, null);
		}
	}

	public record ProfiledParameterBridge(Integer x, String y) {

		@Profile("p")
		public ProfiledParameterBridge(@Field(value = "x", bridge = NumberAsText.class) Integer x) {
			this(x, null);
		}
	}

	public record ProfiledAccessorField(String x, String y) {

		@Profile(value = "p", fields = { "x" })
		public ProfiledAccessorField(String x) {
			this(x, null);
		}

		@Override
		@Field("acc")
		public String x() {
			return x;
		}
	}

	public record ProfiledAccessorBesideParameterField(String x, String y) {

		@Profile("p")
		public ProfiledAccessorBesideParameterField(@Field("x") String x) {
			this(x, null);
		}

		@Override
		@Field(value = "acc", required = true)
		public String x() {
			return x;
		}
	}

	public record ProfiledHeaderUnion(@Union({ String.class, Integer.class }) Object tag, String y) {

		@Profile(value = "p", fields = { "tag" })
		public ProfiledHeaderUnion(Object tag) {
			this(tag, null);
		}
	}

	public record ProfiledParameterUnion(Object tag, String y) {

		@Profile(value = "p", fields = { "tag" })
		public ProfiledParameterUnion(@Union({ String.class, Integer.class }) Object tag) {
			this(tag, null);
		}
	}

	public record ProfiledUnboundAccessor(String name, int cache, String tier) {

		@Profile(value = "p", fields = { "name", "cache" })
		public ProfiledUnboundAccessor(String name, int cache) {
			this(name, cache, null);
		}

		@Override
		@Unbound
		public int cache() {
			return cache;
		}
	}

	// --- a hand-written class bound through its @Record constructor

	public static final class ConstructorStoredField {

		@Field("a")
		private final String x;

		@Record
		public ConstructorStoredField(String x) {
			this.x = x;
		}

		public String x() {
			return x;
		}
	}

	public static final class ConstructorParameter {

		private final String x;

		@Record
		public ConstructorParameter(@Field("a") String x) {
			this.x = x;
		}

		public String x() {
			return x;
		}
	}

	public static final class ConstructorAccessor {

		private final String x;

		@Record
		public ConstructorAccessor(String x) {
			this.x = x;
		}

		@Field("a")
		public String x() {
			return x;
		}
	}

	public static final class ConstructorFieldBesideParameter {

		@Field("a")
		private final String x;

		@Record
		public ConstructorFieldBesideParameter(@Field("b") String x) {
			this.x = x;
		}

		public String x() {
			return x;
		}
	}

	public static final class ConstructorFieldBesideAccessor {

		@Field("a")
		private final String x;

		@Record
		public ConstructorFieldBesideAccessor(String x) {
			this.x = x;
		}

		@Field("b")
		public String x() {
			return x;
		}
	}

	public static final class ConstructorParameterBesideAccessor {

		private final String x;

		@Record
		public ConstructorParameterBesideAccessor(@Field("a") String x) {
			this.x = x;
		}

		@Field("b")
		public String x() {
			return x;
		}
	}

	public static final class ConstructorAgreeingMembers {

		@Field("a")
		private final String x;

		@Record
		public ConstructorAgreeingMembers(@Field("a") String x) {
			this.x = x;
		}

		@Field("a")
		public String x() {
			return x;
		}
	}

	public static final class ConstructorRequiredDiffers {

		@Field(value = "x", required = true)
		private final String x;

		@Record
		public ConstructorRequiredDiffers(String x) {
			this.x = x;
		}

		@Field("x")
		public String x() {
			return x;
		}
	}

	public static final class ConstructorBridgeOnOne {

		private final Integer x;

		@Record
		public ConstructorBridgeOnOne(@Field(value = "x", bridge = NumberAsText.class) Integer x) {
			this.x = x;
		}

		@Field("x")
		public Integer x() {
			return x;
		}
	}

	/** Each member states one attribute. */
	public static final class ConstructorAttributesOnThreeMembers {

		@Field("a")
		private final Integer x;

		@Record
		public ConstructorAttributesOnThreeMembers(@Field(required = true) Integer x) {
			this.x = x;
		}

		@Field(bridge = NumberAsText.class)
		public Integer x() {
			return x;
		}
	}

	public static final class ConstructorBridgesDiffer {

		private final Integer x;

		@Record
		public ConstructorBridgesDiffer(@Field(bridge = NumberAsText.class) Integer x) {
			this.x = x;
		}

		@Field(bridge = RecordingNumberAsText.class)
		public Integer x() {
			return x;
		}
	}

	/** Two members disagree, and a parameter binds nothing. */
	public static final class ConstructorDisagreementBesideUnstoredParameter {

		@Field("a")
		private final String x;

		@Record
		public ConstructorDisagreementBesideUnstoredParameter(@Field("b") String x, String y) {
			this.x = x;
		}

		public String x() {
			return x;
		}
	}

	/** A parameter stored in two fields, the second of which disagrees with the parameter's @Field. */
	public static final class ConstructorParameterInTwoFields {

		private final String x1;

		@Field("q")
		private final String x2;

		@Record
		public ConstructorParameterInTwoFields(@Field("p") String x) {
			this.x1 = x;
			this.x2 = x;
		}

		public String x1() {
			return x1;
		}

		public String x2() {
			return x2;
		}
	}

	/** The accessor returns no field the constructor stores, so only its @Field matches it to the parameter. */
	public static final class ConstructorNamedAccessor {

		private final String raw;

		@Record
		public ConstructorNamedAccessor(@Field("x") String x) {
			this.raw = x.trim();
		}

		@Field("x")
		public String x() {
			return raw;
		}
	}

	public static final class ConstructorNamedAccessorRequired {

		private final String raw;

		@Record
		public ConstructorNamedAccessorRequired(@Field("x") String x) {
			this.raw = x.trim();
		}

		@Field(value = "x", required = true)
		public String x() {
			return raw;
		}
	}

	/** The constructor stores no parameter unchanged, so only the pair finder reads the private field. */
	public static final class ConstructorComputedIntoAnnotatedField {

		@Field(value = "x", required = true)
		private final String x;

		@Record
		public ConstructorComputedIntoAnnotatedField(@Field("x") String x) {
			this.x = x.trim();
		}

		public String x() {
			return x;
		}
	}

	public static final class ConstructorNamedAccessorUnion {

		private final Object held;

		@Record
		public ConstructorNamedAccessorUnion(@Field("tag") Object tag) {
			this.held = Objects.requireNonNull(tag);
		}

		@Union({ String.class, Integer.class })
		public Object tag() {
			return held;
		}
	}

	public static final class ConstructorUnionField {

		@Union({ String.class, Integer.class })
		private final Object tag;

		@Record
		public ConstructorUnionField(Object tag) {
			this.tag = tag;
		}

		public Object tag() {
			return tag;
		}
	}

	public static final class ConstructorUnionParameter {

		private final Object tag;

		@Record
		public ConstructorUnionParameter(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}

		public Object tag() {
			return tag;
		}
	}

	public static final class ConstructorUnionAccessor {

		private final Object tag;

		@Record
		public ConstructorUnionAccessor(Object tag) {
			this.tag = tag;
		}

		@Union({ String.class, Integer.class })
		public Object tag() {
			return tag;
		}
	}

	public static final class ConstructorUnionFieldBesideParameter {

		@Union({ String.class })
		private final Object tag;

		@Record
		public ConstructorUnionFieldBesideParameter(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}

		public Object tag() {
			return tag;
		}
	}

	public static final class ConstructorUnionFieldBesideAccessor {

		@Union({ String.class, Integer.class })
		private final Object tag;

		@Record
		public ConstructorUnionFieldBesideAccessor(Object tag) {
			this.tag = tag;
		}

		@Union({ String.class })
		public Object tag() {
			return tag;
		}
	}

	public static final class ConstructorUnionUnstoredParameter {

		private final Object held;

		@Record
		public ConstructorUnionUnstoredParameter(@Union({ String.class, Integer.class }) Object tag) {
			this.held = Objects.requireNonNull(tag);
		}

		public Object tag() {
			return held;
		}
	}

	// --- a class of public fields

	@Record
	public static class PublicFieldNamed {

		@Field("z")
		public String a;

		public String m;

		public PublicFieldNamed() {
		}
	}

	@Record
	public static class PublicFieldUnion {

		@Union({ String.class, Integer.class })
		public Object tag;

		public PublicFieldUnion() {
		}
	}

	// --- a getter/setter pair

	@Record
	public static class PairGetter {

		private String x;

		public PairGetter() {
		}

		@Field("a")
		public String getX() {
			return x;
		}

		public void setX(String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairSetter {

		private String x;

		public PairSetter() {
		}

		public String getX() {
			return x;
		}

		@Field("a")
		public void setX(String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairPrivateField {

		@Field("a")
		private String x;

		public PairPrivateField() {
		}

		public String getX() {
			return x;
		}

		public void setX(String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairGetterBesideSetter {

		private String x;

		public PairGetterBesideSetter() {
		}

		@Field("a")
		public String getX() {
			return x;
		}

		@Field("b")
		public void setX(String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairGetterBesidePrivateField {

		@Field("b")
		private String x;

		public PairGetterBesidePrivateField() {
		}

		@Field("a")
		public String getX() {
			return x;
		}

		public void setX(String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairSetterBesidePrivateField {

		@Field("b")
		private String x;

		public PairSetterBesidePrivateField() {
		}

		public String getX() {
			return x;
		}

		@Field("a")
		public void setX(String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairSetterParameter {

		private String x;

		public PairSetterParameter() {
		}

		public String getX() {
			return x;
		}

		public void setX(@Field("a") String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairSetterParameterUnion {

		private Object tag;

		public PairSetterParameterUnion() {
		}

		public Object getTag() {
			return tag;
		}

		public void setTag(@Union({ String.class, Integer.class }) Object tag) {
			this.tag = tag;
		}
	}

	@Record
	public static class PairSetterParameterUnbound {

		private String name;

		private String cache;

		public PairSetterParameterUnbound() {
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getCache() {
			return cache;
		}

		public void setCache(@Unbound String cache) {
			this.cache = cache;
		}
	}

	@Record
	public static class PairGetterBesideSetterParameter {

		private String x;

		public PairGetterBesideSetterParameter() {
		}

		@Field("a")
		public String getX() {
			return x;
		}

		public void setX(@Field("b") String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairSetterBesideItsParameter {

		private String x;

		public PairSetterBesideItsParameter() {
		}

		public String getX() {
			return x;
		}

		@Field(required = true)
		public void setX(@Field(required = true) String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairAgreeingMembers {

		@Field("a")
		private String x;

		public PairAgreeingMembers() {
		}

		@Field("a")
		public String getX() {
			return x;
		}

		@Field("a")
		public void setX(String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairRequiredDiffers {

		private String x;

		public PairRequiredDiffers() {
		}

		@Field(value = "x", required = true)
		public String getX() {
			return x;
		}

		@Field("x")
		public void setX(String x) {
			this.x = x;
		}
	}

	@Record
	public static class PairBridgeOnOne {

		@Field("x")
		private Integer x;

		public PairBridgeOnOne() {
		}

		@Field(value = "x", bridge = NumberAsText.class)
		public Integer getX() {
			return x;
		}

		public void setX(Integer x) {
			this.x = x;
		}
	}

	@Record
	public static class PairGetterUnion {

		private Object tag;

		public PairGetterUnion() {
		}

		@Union({ String.class, Integer.class })
		public Object getTag() {
			return tag;
		}

		public void setTag(Object tag) {
			this.tag = tag;
		}
	}

	@Record
	public static class PairPrivateFieldUnion {

		@Union({ String.class, Integer.class })
		private Object tag;

		public PairPrivateFieldUnion() {
		}

		public Object getTag() {
			return tag;
		}

		public void setTag(Object tag) {
			this.tag = tag;
		}
	}

	@Record
	public static class PairUnionsDiffer {

		@Union({ String.class, Integer.class })
		private Object tag;

		public PairUnionsDiffer() {
		}

		@Union({ String.class })
		public Object getTag() {
			return tag;
		}

		public void setTag(Object tag) {
			this.tag = tag;
		}
	}

	public static class PairBase {

		@Field("a")
		private String x;

		public String getX() {
			return x;
		}

		public void setX(String x) {
			this.x = x;
		}
	}

	/** The private field behind the pair is the super class's, which declares the pair. */
	@Record
	public static class PairInheritedPrivateField extends PairBase {

		public PairInheritedPrivateField() {
		}
	}

	/** The pairs sort by wire name, which the first pair takes from its private field. */
	@Record
	public static class PairOrderFollowsPrivateFieldName {

		@Field("z")
		private String a;

		private String m;

		public PairOrderFollowsPrivateFieldName() {
		}

		public String getA() {
			return a;
		}

		public void setA(String a) {
			this.a = a;
		}

		public String getM() {
			return m;
		}

		public void setM(String m) {
			this.m = m;
		}
	}

	@Record
	public static class PairOrphanPrivateField {

		@Field("o")
		private String orphan;

		private String x;

		public PairOrphanPrivateField() {
		}

		public String getX() {
			return x;
		}

		public void setX(String x) {
			this.x = x;
		}
	}

	/** A pair's members disagree, and a private field's @Field names no component. */
	@Record
	public static class PairDisagreementBesideOrphan {

		@Field("o")
		private String orphan;

		private String x;

		public PairDisagreementBesideOrphan() {
		}

		@Field("a")
		public String getX() {
			return x;
		}

		@Field("b")
		public void setX(String x) {
			this.x = x;
		}
	}

	// --- a constructor component with a setter merged in

	public static final class MergedSetterField {

		private String count;

		@Record
		public MergedSetterField(String count) {
			this.count = count;
		}

		public String getCount() {
			return count;
		}

		@Field("s")
		public void setCount(String count) {
			this.count = count;
		}
	}

	public static final class MergedSetterParameter {

		private String count;

		@Record
		public MergedSetterParameter(String count) {
			this.count = count;
		}

		public String getCount() {
			return count;
		}

		public void setCount(@Field("s") String count) {
			this.count = count;
		}
	}

	public static final class MergedSetterParameterBesideStoredField {

		@Field("a")
		private String count;

		@Record
		public MergedSetterParameterBesideStoredField(String count) {
			this.count = count;
		}

		public String getCount() {
			return count;
		}

		public void setCount(@Field("s") String count) {
			this.count = count;
		}
	}

	public static final class MergedSetterRequired {

		private String count;

		@Record
		public MergedSetterRequired(String count) {
			this.count = count;
		}

		public String getCount() {
			return count;
		}

		@Field(value = "count", required = true)
		public void setCount(String count) {
			this.count = count;
		}
	}

	public static final class MergedSetterBesideStoredField {

		@Field("a")
		private String count;

		@Record
		public MergedSetterBesideStoredField(String count) {
			this.count = count;
		}

		public String getCount() {
			return count;
		}

		@Field("s")
		public void setCount(String count) {
			this.count = count;
		}
	}

	public static final class MergedGetterField {

		private String count;

		@Record
		public MergedGetterField(String count) {
			this.count = count;
		}

		@Field("g")
		public String getCount() {
			return count;
		}

		public void setCount(String count) {
			this.count = count;
		}
	}

	public static final class MergedGetterBesideSetter {

		private String count;

		@Record
		public MergedGetterBesideSetter(String count) {
			this.count = count;
		}

		@Field("g")
		public String getCount() {
			return count;
		}

		@Field("s")
		public void setCount(String count) {
			this.count = count;
		}
	}

	public static final class MergedSetterUnion {

		private Object tag;

		@Record
		public MergedSetterUnion(Object tag) {
			this.tag = tag;
		}

		public Object getTag() {
			return tag;
		}

		@Union({ String.class, Integer.class })
		public void setTag(Object tag) {
			this.tag = tag;
		}
	}

	public static final class MergedPublicField {

		@Field("a")
		public String count;

		@Record
		public MergedPublicField(String count) {
			this.count = count;
		}
	}

	public static final class MergedPublicFieldUnion {

		@Union({ String.class, Integer.class })
		public Object tag;

		@Record
		public MergedPublicFieldUnion(Object tag) {
			this.tag = tag;
		}
	}

	public static final class MergedSetterUnbound {

		private String name;

		private int count;

		@Record
		public MergedSetterUnbound(String name, int count) {
			this.name = name;
			this.count = count;
		}

		public String name() {
			return name;
		}

		public int getCount() {
			return count;
		}

		@Unbound
		public void setCount(int count) {
			this.count = count;
		}
	}
}
