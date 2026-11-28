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
import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Tuple;
import io.ltr8.annotation.Unbound;
import io.ltr8.annotation.Union;
import io.ltr8.test.data.BridgedFields.NumberAsText;

import java.util.Optional;

/**
 * A binding annotation where nothing would apply it, each beside a class that writes the same thing where it applies.
 */
public final class IgnoredAnnotations {

	private IgnoredAnnotations() {
	}

	/** Any object written as its text, read back as that text. */
	public static class ObjectAsText implements DataBridge<String, Object> {

		@Override
		public String toData(Object value) {
			return value == null ? null : value.toString();
		}

		@Override
		public Object toObject(String text) {
			return text;
		}
	}

	// --- a component marked @Unbound

	public record UnboundRequired(String name, @Unbound @Field(required = true) String cache) {
	}

	public record UnboundBridged(String name, @Unbound @Field(bridge = NumberAsText.class) Integer cache) {
	}

	public record UnboundUnion(String name, @Unbound @Union({ String.class, Integer.class }) Object cache) {
	}

	/** Its wire name keeps it apart from the other components, and a reader recognises it by that name. */
	public record UnboundRenamed(String name, @Unbound @Field("c") String cache) {
	}

	/** The parameter states the component unbound; the accessor states it required. */
	public static final class UnboundParameterRequiredAccessor {

		private final String name;

		private final String cache;

		@Record
		public UnboundParameterRequiredAccessor(String name, @Unbound String cache) {
			this.name = name;
			this.cache = cache;
		}

		public String name() {
			return name;
		}

		@Field(required = true)
		public String cache() {
			return cache;
		}
	}

	// --- a @Union something else decides past

	public record UnionBesideBridge(@Field(bridge = ObjectAsText.class) @Union({ String.class, Integer.class })
			Object tag) {
	}

	public record UnionOnOptional(@Union({ String.class }) Optional<Object> tag) {
	}

	public record UnionOnCarrier(String name, @Union({ Annotations.class }) Annotations notes) {
	}

	public record BridgeOnCarrier(String name, @Field(bridge = NumberAsText.class) Annotations notes) {
	}

	/** Required on the carrier is true already: a carrier is always present. */
	public record RequiredOnCarrier(String name, @Field(required = true) Annotations notes) {
	}

	// --- a @Tuple record

	@Tuple
	public record TupleField(@Field("a") String x, int y) {
	}

	@Tuple
	public record TupleUnion(@Union({ String.class }) String x) {
	}

	@Tuple
	public record TupleUnbound(String x, @Unbound int y) {
	}

	@Tuple
	public record TupleAccessorField(String x) {

		@Override
		@Field(required = true)
		public String x() {
			return x;
		}
	}

	// --- a @Transparent record

	@Transparent
	public record TransparentField(@Field("a") String value) {
	}

	@Transparent
	public record TransparentUnion(@Union({ String.class }) String value) {
	}

	@Transparent
	public record TransparentUnbound(@Unbound String value) {
	}

	// --- a member of no component

	@Record
	public static class PrivateUnionOfNoComponent {

		@Union({ String.class, Integer.class })
		private Object orphan;

		private String x;

		public PrivateUnionOfNoComponent() {
		}

		public String getX() {
			return x;
		}

		public void setX(String x) {
			this.x = x;
		}
	}

	@Record
	public static class PrivateUnboundOfNoComponent {

		@Unbound
		private String orphan;

		private String x;

		public PrivateUnboundOfNoComponent() {
		}

		public String getX() {
			return x;
		}

		public void setX(String x) {
			this.x = x;
		}
	}

	public static class FieldOfNoComponentBase {

		@Field("h")
		private String hidden;
	}

	/** The super class's private field is behind no pair or accessor. */
	@Record
	public static class InheritedFieldOfNoComponent extends FieldOfNoComponentBase {

		private String x;

		public InheritedFieldOfNoComponent() {
		}

		public String getX() {
			return x;
		}

		public void setX(String x) {
			this.x = x;
		}
	}

	public record StaticFieldAnnotated(String x) {

		@Field("k")
		public static final String KEY = "key";
	}

	public record StaticMethodAnnotated(String x) {

		@Field("made")
		public static StaticMethodAnnotated make() {
			return new StaticMethodAnnotated("x");
		}
	}

	/** A getter with no setter is no pair. */
	@Record
	public static class GetterOfNoPair {

		private String x;

		public GetterOfNoPair() {
		}

		public String getX() {
			return x;
		}

		public void setX(String x) {
			this.x = x;
		}

		@Field("only")
		public String getOnly() {
			return "only";
		}
	}

	public record MethodOfNoComponent(String x) {

		@Union({ String.class })
		public Object derived() {
			return x;
		}
	}

	// --- annotations another reader applies

	/** The profile constructor's parameter is read on its own profile's path. */
	public record ParameterOfAnotherConstructor(String x, String y) {

		@Profile(value = "p", fields = { "x" })
		public ParameterOfAnotherConstructor(@Field("b") String x) {
			this(x, null);
		}
	}

	/** The omitted component's annotation is read on the canonical path. */
	public record ComponentOfAnotherProfile(String x, @Field("why") String y) {

		@Profile(value = "p", fields = { "x" })
		public ComponentOfAnotherProfile(String x) {
			this(x, null);
		}
	}

	public record RequiredPrimitive(@Field(required = true) int count) {
	}
}
