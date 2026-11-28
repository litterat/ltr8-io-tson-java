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
package io.ltr8.test.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.test.data.BridgedFields.NumberAsText;
import io.ltr8.test.data.IgnoredAnnotations.BridgeOnCarrier;
import io.ltr8.test.data.IgnoredAnnotations.ComponentOfAnotherProfile;
import io.ltr8.test.data.IgnoredAnnotations.GetterOfNoPair;
import io.ltr8.test.data.IgnoredAnnotations.InheritedFieldOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.MethodOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.ObjectAsText;
import io.ltr8.test.data.IgnoredAnnotations.ParameterOfAnotherConstructor;
import io.ltr8.test.data.IgnoredAnnotations.PrivateUnboundOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.PrivateUnionOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.RequiredOnCarrier;
import io.ltr8.test.data.IgnoredAnnotations.RequiredPrimitive;
import io.ltr8.test.data.IgnoredAnnotations.StaticFieldAnnotated;
import io.ltr8.test.data.IgnoredAnnotations.StaticMethodAnnotated;
import io.ltr8.test.data.IgnoredAnnotations.TransparentField;
import io.ltr8.test.data.IgnoredAnnotations.TransparentUnbound;
import io.ltr8.test.data.IgnoredAnnotations.TransparentUnion;
import io.ltr8.test.data.IgnoredAnnotations.TupleAccessorField;
import io.ltr8.test.data.IgnoredAnnotations.TupleField;
import io.ltr8.test.data.IgnoredAnnotations.TupleUnbound;
import io.ltr8.test.data.IgnoredAnnotations.TupleUnion;
import io.ltr8.test.data.IgnoredAnnotations.UnboundBridged;
import io.ltr8.test.data.IgnoredAnnotations.UnboundParameterRequiredAccessor;
import io.ltr8.test.data.IgnoredAnnotations.UnboundRenamed;
import io.ltr8.test.data.IgnoredAnnotations.UnboundRequired;
import io.ltr8.test.data.IgnoredAnnotations.UnboundUnion;
import io.ltr8.test.data.IgnoredAnnotations.UnionBesideBridge;
import io.ltr8.test.data.IgnoredAnnotations.UnionOnCarrier;
import io.ltr8.test.data.IgnoredAnnotations.UnionOnOptional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A binding annotation is never silently ignored: it is applied, or the class is refused, naming the class, the
 * member carrying the annotation, the annotation and why nothing applies it there.
 */
class IgnoredAnnotationRefusalTest {

	private static final DataBindContext PLAIN = DataBindContext.builder().build();

	private static final DataBindContext PROFILED = DataBindContext.builder().profile("p").build();

	private static final String BOTH = "@Union({java.lang.String.class, java.lang.Integer.class})";

	@Test
	void anUnboundComponentRefusesWhatOnlyABoundOneUses() {
		assertAll(
				refused(UnboundRequired.class, unbound(UnboundRequired.class, "cache",
						"the field UnboundRequired.cache", "@Field(required = true)")),
				refused(UnboundBridged.class, unbound(UnboundBridged.class, "cache", "the field UnboundBridged.cache",
						"@Field(bridge = " + NumberAsText.class.getName() + ".class)")),
				refused(UnboundUnion.class, unbound(UnboundUnion.class, "cache", "the field UnboundUnion.cache",
						BOTH)),
				refused(UnboundParameterRequiredAccessor.class, unbound(UnboundParameterRequiredAccessor.class,
						"cache", "the accessor UnboundParameterRequiredAccessor.cache()", "@Field(required = true)")));
	}

	/** Its name keeps it apart from the class's other components, and a reader recognises it by that name. */
	@Test
	void anUnboundComponentTakesItsWireName() {
		assertAll(described(PLAIN, UnboundRenamed.class, "name"));
	}

	@Test
	void aUnionNothingConsultsIsRefused() {
		assertAll(
				refused(UnionBesideBridge.class, UnionBesideBridge.class.getName() + "'s component 'tag' has " + BOTH
						+ " on the field UnionBesideBridge.tag, which nothing consults: its @Field(bridge = "
						+ ObjectAsText.class.getName() + ".class) decides its data class"),
				refused(UnionOnOptional.class, UnionOnOptional.class.getName() + "'s component 'tag' has "
						+ "@Union({java.lang.String.class}) on the field UnionOnOptional.tag, which nothing consults: "
						+ "its type java.util.Optional decides its data class"));
	}

	@Test
	void theAnnotationsCarrierRefusesABridgeOrAUnion() {
		assertAll(
				refused(UnionOnCarrier.class, carrier(UnionOnCarrier.class, "the field UnionOnCarrier.notes",
						"@Union({io.ltr8.annotation.Annotations.class})")),
				refused(BridgeOnCarrier.class, carrier(BridgeOnCarrier.class, "the field BridgeOnCarrier.notes",
						"@Field(bridge = " + NumberAsText.class.getName() + ".class)")),
				described(PLAIN, RequiredOnCarrier.class, "name, notes"));
	}

	@Test
	void aTupleRefusesEveryBindingAnnotationOnItsComponents() {
		assertAll(
				refused(TupleField.class, tuple(TupleField.class, "@Field", "the field TupleField.x")),
				refused(TupleUnion.class, tuple(TupleUnion.class, "@Union", "the field TupleUnion.x")),
				refused(TupleUnbound.class, tuple(TupleUnbound.class, "@Unbound", "the field TupleUnbound.y")),
				refused(TupleAccessorField.class, tuple(TupleAccessorField.class, "@Field",
						"the method TupleAccessorField.x()")));
	}

	@Test
	void aTransparentRecordRefusesEveryBindingAnnotationOnItsComponent() {
		assertAll(
				refused(TransparentField.class, transparent(TransparentField.class, "@Field",
						"the field TransparentField.value")),
				refused(TransparentUnion.class, transparent(TransparentUnion.class, "@Union",
						"the field TransparentUnion.value")),
				refused(TransparentUnbound.class, transparent(TransparentUnbound.class, "@Unbound",
						"the field TransparentUnbound.value")));
	}

	@Test
	void aMemberOfNoComponentRefusesItsAnnotation() {
		assertAll(
				refused(PrivateUnionOfNoComponent.class, noComponent(PrivateUnionOfNoComponent.class,
						"field PrivateUnionOfNoComponent.orphan", "@Union")),
				refused(PrivateUnboundOfNoComponent.class, noComponent(PrivateUnboundOfNoComponent.class,
						"field PrivateUnboundOfNoComponent.orphan", "@Unbound")),
				refused(InheritedFieldOfNoComponent.class, noComponent(InheritedFieldOfNoComponent.class,
						"field FieldOfNoComponentBase.hidden", "@Field")),
				refused(StaticFieldAnnotated.class, noComponent(StaticFieldAnnotated.class,
						"static field StaticFieldAnnotated.KEY", "@Field")),
				refused(StaticMethodAnnotated.class, noComponent(StaticMethodAnnotated.class,
						"static method StaticMethodAnnotated.make()", "@Field")),
				refused(GetterOfNoPair.class, noComponent(GetterOfNoPair.class, "method GetterOfNoPair.getOnly()",
						"@Field")),
				refused(MethodOfNoComponent.class, noComponent(MethodOfNoComponent.class,
						"method MethodOfNoComponent.derived()", "@Union")));
	}

	@Test
	void anAnnotationAnotherPathAppliesIsNotRefused() {
		assertAll(
				described(PLAIN, ParameterOfAnotherConstructor.class, "x, y"),
				described(PROFILED, ParameterOfAnotherConstructor.class, "b"),
				described(PLAIN, ComponentOfAnotherProfile.class, "x, why"),
				described(PROFILED, ComponentOfAnotherProfile.class, "x"),
				described(PLAIN, RequiredPrimitive.class, "count"));
	}

	private static String unbound(Class<?> type, String component, String member, String annotation) {
		return "%s's component '%s' is @Unbound, so nothing reads or writes it, but %s carries %s".formatted(
				type.getName(), component, member, annotation);
	}

	private static String carrier(Class<?> type, String member, String annotation) {
		return ("%s's component 'notes' is its Annotations carrier, filled from the value's own annotations, but %s "
				+ "carries %s").formatted(type.getName(), member, annotation);
	}

	private static String tuple(Class<?> type, String annotation, String member) {
		return "%s is a @Tuple, written as its components by position, so nothing reads %s on %s".formatted(
				type.getName(), annotation, member);
	}

	private static String transparent(Class<?> type, String annotation, String member) {
		return "%s is @Transparent, written as its one component, so nothing reads %s on %s".formatted(
				type.getName(), annotation, member);
	}

	private static String noComponent(Class<?> type, String member, String annotation) {
		return "%s's %s carries %s, but is a member of no component, so nothing reads it".formatted(type.getName(),
				member, annotation);
	}

	private static Executable refused(Class<?> type, String message) {
		return () -> assertEquals("refused: " + message, describe(PLAIN, type), type.getSimpleName());
	}

	private static Executable described(DataBindContext context, Class<?> type, String names) {
		return () -> assertEquals(names, describe(context, type), type.getSimpleName());
	}

	private static String describe(DataBindContext context, Class<?> type) {
		try {
			return context.getDescriptor(type) instanceof DataClassRecord record
					? Stream.of(record.fields()).map(field -> field.name()).collect(Collectors.joining(", "))
					: "described";
		} catch (Exception e) {
			return "refused: " + e.getMessage();
		}
	}
}
