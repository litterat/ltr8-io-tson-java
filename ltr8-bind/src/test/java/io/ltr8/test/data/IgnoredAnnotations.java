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
import io.ltr8.annotation.Atom;
import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Tuple;
import io.ltr8.annotation.Unbound;
import io.ltr8.annotation.Union;
import io.ltr8.test.data.BridgedFields.NumberAsText;

import java.util.Objects;
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

	// --- a method that overrides an annotated one, or is overridden by one

	public static class LabelledBase {

		protected final String name;

		public LabelledBase(String name) {
			this.name = name;
		}

		@Field("label")
		public String name() {
			return name;
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && obj.getClass() == getClass() && Objects.equals(name, ((LabelledBase) obj).name);
		}

		@Override
		public int hashCode() {
			return Objects.hashCode(name);
		}
	}

	/** Its accessor overrides the super class's, which names the component. */
	public static class OverrideKeepsLabel extends LabelledBase {

		@Record
		public OverrideKeepsLabel(String name) {
			super(name);
		}

		@Override
		public String name() {
			return name;
		}
	}

	public static class OverrideRenames extends LabelledBase {

		@Record
		public OverrideRenames(String name) {
			super(name);
		}

		@Override
		@Field("renamed")
		public String name() {
			return name;
		}
	}

	/** The super class's accessor is the one matched; the override, which a call dispatches to, marks it unbound. */
	public static class OverrideUnbinds extends LabelledBase {

		private final int count;

		@Record
		public OverrideUnbinds(String name, int count) {
			super(name);
			this.count = count;
		}

		@Override
		@Unbound
		public String name() {
			return Objects.requireNonNull(super.name());
		}

		public int count() {
			return count;
		}
	}

	public static class TitledBase {

		private String title;

		@Field("heading")
		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}
	}

	@Record
	public static class GetterOverrideKeepsLabel extends TitledBase {

		@Override
		public String getTitle() {
			return super.getTitle();
		}
	}

	public static class ValueBase<T> {

		private T value;

		public T getValue() {
			return value;
		}

		@Field("v")
		public void setValue(T value) {
			this.value = value;
		}
	}

	/** Its setter overrides one of another erasure, through a bridge. */
	@Record
	public static class GenericSetterOverride extends ValueBase<String> {

		@Override
		public String getValue() {
			return super.getValue();
		}

		@Override
		public void setValue(String value) {
			super.setValue(value);
		}
	}

	public static class PrivateNameBase {

		@Field("hidden")
		private String name() {
			return "hidden";
		}
	}

	/** A private method is overridden by nothing. */
	public static class PrivateMethodOfSameName extends PrivateNameBase {

		private final String name;

		@Record
		public PrivateMethodOfSameName(String name) {
			this.name = name;
		}

		public String name() {
			return name;
		}
	}

	// --- an interface's method

	public interface Labelled {

		@Field("label")
		String name();
	}

	public record ImplementsLabelled(String name) implements Labelled {
	}

	public record RenamesLabelled(@Field("renamed") String name) implements Labelled {
	}

	public interface Titled {

		@Field("heading")
		String getTitle();
	}

	@Record
	public static class GetterImplementsTitled implements Titled {

		private String title;

		@Override
		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}
	}

	public interface Cached {

		@Unbound
		String cache();
	}

	public record ImplementsCached(String name, String cache) implements Cached {
	}

	public interface Derived {

		@Field("d")
		String derived();
	}

	public record DerivedOfNoComponent(String x) implements Derived {

		@Override
		public String derived() {
			return x;
		}
	}

	/** An interface's field is inherited, and static. */
	public interface Keyed {

		@Field("k")
		String KEY = "key";
	}

	public record ImplementsKeyed(String x) implements Keyed {
	}

	// --- a class described by no components of its own

	public record Plain(String x) {
	}

	public static final class BridgedWithField implements ToData<Plain> {

		@Field("y")
		private final String x;

		public BridgedWithField(Plain plain) {
			this.x = plain.x();
		}

		@Override
		public Plain toData() {
			return new Plain(x);
		}
	}

	public static final class BridgedWithParameter implements ToData<Plain> {

		private final String x;

		public BridgedWithParameter(@Unbound Plain plain) {
			this.x = plain.x();
		}

		@Override
		public Plain toData() {
			return new Plain(x);
		}
	}

	public static final class AtomWithMethod {

		private final String text;

		@Atom
		public AtomWithMethod(String text) {
			this.text = text;
		}

		public String toData() {
			return text;
		}

		@Field("t")
		public String text() {
			return text;
		}
	}

	public enum ConstantWithField {
		@Field("r")
		RED, GREEN
	}

	public enum EnumWithField {
		RED, GREEN;

		@Unbound
		private int code;
	}

	public enum ConstructorParameterOfEnum {
		RED("r");

		ConstructorParameterOfEnum(@Field("c") String code) {
		}
	}

	public enum ConstantBodyWithMethod {
		RED {
			@Override
			@Union({ String.class })
			public Object code() {
				return "r";
			}
		};

		public abstract Object code();
	}

	@Union({ ImplementsLabelled.class })
	public interface UnionWithStatic {

		@Field("k")
		String KEY = "key";
	}

	/** The members read the union's method as their component's. */
	public sealed interface LabelledUnion permits LabelledMember {

		@Field("label")
		String name();
	}

	public record LabelledMember(String name) implements LabelledUnion {
	}

	// --- a protocol

	public record Ask(String text) {
	}

	public interface MethodAnnotatedProtocol {

		@Field("asked")
		Ask ask(Ask question);
	}

	public interface ParameterAnnotatedProtocol {

		Ask ask(@Union({ Ask.class }) Ask question);
	}
}
