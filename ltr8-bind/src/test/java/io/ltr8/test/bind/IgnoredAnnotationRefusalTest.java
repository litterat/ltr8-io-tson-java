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
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.BridgedFields.NumberAsText;
import io.ltr8.test.data.IgnoredAnnotations.AtomWithMethod;
import io.ltr8.test.data.IgnoredAnnotations.BridgeOnCarrier;
import io.ltr8.test.data.IgnoredAnnotations.BridgedWithField;
import io.ltr8.test.data.IgnoredAnnotations.BridgedWithParameter;
import io.ltr8.test.data.IgnoredAnnotations.ComponentOfAnotherProfile;
import io.ltr8.test.data.IgnoredAnnotations.ConstantBodyMethodParameter;
import io.ltr8.test.data.IgnoredAnnotations.ConstantBodyWithMethod;
import io.ltr8.test.data.IgnoredAnnotations.ConstantWithField;
import io.ltr8.test.data.IgnoredAnnotations.ConstructorParameterOfEnum;
import io.ltr8.test.data.IgnoredAnnotations.DerivedOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.EnumMethodParameter;
import io.ltr8.test.data.IgnoredAnnotations.EnumWithField;
import io.ltr8.test.data.IgnoredAnnotations.GenericSetterOverride;
import io.ltr8.test.data.IgnoredAnnotations.GetterImplementsTitled;
import io.ltr8.test.data.IgnoredAnnotations.GetterOfNoPair;
import io.ltr8.test.data.IgnoredAnnotations.GetterOverrideKeepsLabel;
import io.ltr8.test.data.IgnoredAnnotations.ImplementsCached;
import io.ltr8.test.data.IgnoredAnnotations.ImplementsKeyed;
import io.ltr8.test.data.IgnoredAnnotations.ImplementsLabelled;
import io.ltr8.test.data.IgnoredAnnotations.ImplementsTouched;
import io.ltr8.test.data.IgnoredAnnotations.InheritedFieldOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.LabelledMember;
import io.ltr8.test.data.IgnoredAnnotations.LabelledUnion;
import io.ltr8.test.data.IgnoredAnnotations.ListWithField;
import io.ltr8.test.data.IgnoredAnnotations.MapWithParameter;
import io.ltr8.test.data.IgnoredAnnotations.MethodAnnotatedProtocol;
import io.ltr8.test.data.IgnoredAnnotations.MethodOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.MethodParameterOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.ObjectAsText;
import io.ltr8.test.data.IgnoredAnnotations.OverrideKeepsLabel;
import io.ltr8.test.data.IgnoredAnnotations.OverrideRenames;
import io.ltr8.test.data.IgnoredAnnotations.OverrideUnbinds;
import io.ltr8.test.data.IgnoredAnnotations.ParameterAnnotatedProtocol;
import io.ltr8.test.data.IgnoredAnnotations.ParameterOfAnotherConstructor;
import io.ltr8.test.data.IgnoredAnnotations.Plain;
import io.ltr8.test.data.IgnoredAnnotations.PrivateMethodOfSameName;
import io.ltr8.test.data.IgnoredAnnotations.ProfiledStaticMethodParameter;
import io.ltr8.test.data.IgnoredAnnotations.RenamesLabelled;
import io.ltr8.test.data.IgnoredAnnotations.PrivateUnboundOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.PrivateUnionOfNoComponent;
import io.ltr8.test.data.IgnoredAnnotations.RequiredOnCarrier;
import io.ltr8.test.data.IgnoredAnnotations.RequiredPrimitive;
import io.ltr8.test.data.IgnoredAnnotations.SetterOverrideKeepsParameterLabel;
import io.ltr8.test.data.IgnoredAnnotations.SetterParameterOfNoPair;
import io.ltr8.test.data.IgnoredAnnotations.StaticFieldAnnotated;
import io.ltr8.test.data.IgnoredAnnotations.StaticMethodAnnotated;
import io.ltr8.test.data.IgnoredAnnotations.StaticMethodParameterAnnotated;
import io.ltr8.test.data.IgnoredAnnotations.TransparentField;
import io.ltr8.test.data.IgnoredAnnotations.TransparentUnbound;
import io.ltr8.test.data.IgnoredAnnotations.TransparentUnion;
import io.ltr8.test.data.IgnoredAnnotations.TupleAccessorField;
import io.ltr8.test.data.IgnoredAnnotations.TupleField;
import io.ltr8.test.data.IgnoredAnnotations.TupleMethodParameter;
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
import io.ltr8.test.data.IgnoredAnnotations.UnionWithStatic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
	void aParameterOfAMethodThatIsNoSetterOfAComponentRefusesItsAnnotation() {
		assertAll(
				refusedAlone(DataBindContext::builder, SetterParameterOfNoPair.class,
						noComponent(SetterParameterOfNoPair.class,
								"parameter 0 of the method SetterParameterOfNoPair.setOnly(String)", "@Field")),
				refusedAlone(DataBindContext::builder, MethodParameterOfNoComponent.class,
						noComponent(MethodParameterOfNoComponent.class,
								"parameter 0 of the method MethodParameterOfNoComponent.with(Object)", "@Union")),
				refusedAlone(DataBindContext::builder, StaticMethodParameterAnnotated.class,
						noComponent(StaticMethodParameterAnnotated.class,
								"parameter 0 of the method StaticMethodParameterAnnotated.of(String)", "@Unbound")),
				refusedAlone(DataBindContext::builder, ImplementsTouched.class, noComponent(ImplementsTouched.class,
						"parameter 0 of the method Touched.touch(String)", "@Field")),
				refusedAlone(DataBindContext::builder, ProfiledStaticMethodParameter.class,
						noComponent(ProfiledStaticMethodParameter.class,
								"parameter 0 of the method ProfiledStaticMethodParameter.of(String)", "@Field")),
				refusedAlone(() -> DataBindContext.builder().profile("p"), ProfiledStaticMethodParameter.class,
						noComponent(ProfiledStaticMethodParameter.class,
								"parameter 0 of the method ProfiledStaticMethodParameter.of(String)", "@Field")));
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

	/** A call of an accessor, getter or setter dispatches through every method it overrides. */
	@Test
	void anOverriddenMethodsAnnotationIsTheComponents() throws Throwable {
		assertAll(
				described(PLAIN, OverrideKeepsLabel.class, "label"),
				described(PLAIN, OverrideUnbinds.class, "count"),
				described(PLAIN, GetterOverrideKeepsLabel.class, "heading"),
				described(PLAIN, GenericSetterOverride.class, "v"),
				refused(OverrideRenames.class, OverrideRenames.class.getName() + "'s component 'name' has "
						+ "@Field(value = \"renamed\") on the accessor OverrideRenames.name() but "
						+ "@Field(value = \"label\") on the overridden method LabelledBase.name()"),
				refused(PrivateMethodOfSameName.class, noComponent(PrivateMethodOfSameName.class,
						"method PrivateNameBase.name()", "@Field")));
		roundTrip(new OverrideKeepsLabel("v"), Map.of("label", "v"), new Object[] { "v" });
		GetterOverrideKeepsLabel titled = new GetterOverrideKeepsLabel();
		titled.setTitle("t");
		MapMapper maps = new MapMapper(PLAIN);
		assertEquals(Map.of("heading", "t"), maps.toMap(titled));
		assertEquals("t", ((GetterOverrideKeepsLabel) maps.toObject(GetterOverrideKeepsLabel.class,
				maps.toMap(titled))).getTitle());
		ArrayMapper arrays = new ArrayMapper(PLAIN);
		assertArrayEquals(new Object[] { "t" }, arrays.toArray(titled));
		assertEquals("t", arrays.toObject(GetterOverrideKeepsLabel.class, arrays.toArray(titled)).getTitle());
	}

	@Test
	void anOverriddenSettersParameterIsTheComponents() throws Throwable {
		assertAll(described(PLAIN, SetterOverrideKeepsParameterLabel.class, "heading"));
		SetterOverrideKeepsParameterLabel titled = new SetterOverrideKeepsParameterLabel();
		titled.setTitle("t");
		MapMapper maps = new MapMapper(PLAIN);
		assertEquals(Map.of("heading", "t"), maps.toMap(titled));
		assertEquals("t", ((SetterOverrideKeepsParameterLabel) maps.toObject(SetterOverrideKeepsParameterLabel.class,
				maps.toMap(titled))).getTitle());
		ArrayMapper arrays = new ArrayMapper(PLAIN);
		assertArrayEquals(new Object[] { "t" }, arrays.toArray(titled));
		assertEquals("t", arrays.toObject(SetterOverrideKeepsParameterLabel.class, arrays.toArray(titled))
				.getTitle());
	}

	@Test
	void anInterfaceMethodsAnnotationIsTheComponentsWhereItIsImplementedByAMember() throws Throwable {
		assertAll(
				described(PLAIN, ImplementsLabelled.class, "label"),
				described(PLAIN, GetterImplementsTitled.class, "heading"),
				described(PLAIN, ImplementsCached.class, "name"),
				described(PLAIN, LabelledMember.class, "label"),
				described(PLAIN, LabelledUnion.class, "described"),
				refused(RenamesLabelled.class, RenamesLabelled.class.getName() + "'s component 'name' has "
						+ "@Field(value = \"renamed\") on the field RenamesLabelled.name but @Field(value = \"label\") "
						+ "on the overridden method Labelled.name()"),
				refused(DerivedOfNoComponent.class, noComponent(DerivedOfNoComponent.class,
						"method Derived.derived()", "@Field")),
				refused(ImplementsKeyed.class, noComponent(ImplementsKeyed.class, "static field Keyed.KEY",
						"@Field")));
		roundTrip(new ImplementsLabelled("v"), Map.of("label", "v"), new Object[] { "v" });
	}

	@Test
	void aClassDescribedByNoComponentsOfItsOwnRefusesEveryBindingAnnotationOnItsMembers() {
		String plain = Plain.class.getName();
		assertAll(
				refused(BridgedWithField.class, BridgedWithField.class.getName() + " is bridged through ToData to "
						+ plain + ", which describes it, so nothing reads @Field on the field BridgedWithField.x"),
				refused(BridgedWithParameter.class, BridgedWithParameter.class.getName() + " is bridged through "
						+ "ToData to " + plain + ", which describes it, so nothing reads @Unbound on parameter 0 of the "
						+ "constructor BridgedWithParameter(Plain)"),
				refused(AtomWithMethod.class, AtomWithMethod.class.getName() + " is an atom bridged to "
						+ "java.lang.String, so nothing reads @Field on the method AtomWithMethod.text()"),
				refused(ConstantWithField.class, ConstantWithField.class.getName() + " is an enum, written as its "
						+ "constant's name, so nothing reads @Field on the field ConstantWithField.RED"),
				refused(EnumWithField.class, EnumWithField.class.getName() + " is an enum, written as its "
						+ "constant's name, so nothing reads @Unbound on the field EnumWithField.code"),
				refused(ConstantBodyWithMethod.class, ConstantBodyWithMethod.class.getName() + " is an enum, "
						+ "written as its constant's name, so nothing reads @Union on the method code() of the body of "
						+ "its constant RED"),
				refused(ConstructorParameterOfEnum.class, ConstructorParameterOfEnum.class.getName() + " is an "
						+ "enum, written as its constant's name, so nothing reads @Field on parameter 0 of the "
						+ "constructor ConstructorParameterOfEnum(String)"),
				refused(UnionWithStatic.class, UnionWithStatic.class.getName() + " is a union, written as one of "
						+ "its members, so nothing reads @Field on the field UnionWithStatic.KEY"));
	}

	@Test
	void aMapOrACollectionRefusesEveryBindingAnnotationOnItsMembers() {
		assertAll(
				refusedAt(new DataParameterizedType(ListWithField.class, String.class), ListWithField.class.getName()
						+ " is a collection, written as its elements, so nothing reads @Field on the field "
						+ "ListWithField.note"),
				refusedAt(new DataParameterizedType(MapWithParameter.class, String.class, String.class),
						MapWithParameter.class.getName() + " is a map, written as its entries, so nothing reads "
								+ "@Unbound on parameter 0 of the constructor MapWithParameter(int)"));
	}

	@Test
	void aClassDescribedByNoComponentsOfItsOwnRefusesEveryBindingAnnotationOnItsMethodsParameters() {
		assertAll(
				refusedAlone(DataBindContext::builder, EnumMethodParameter.class, EnumMethodParameter.class.getName()
						+ " is an enum, written as its constant's name, so nothing reads @Field on parameter 0 of the "
						+ "method EnumMethodParameter.code(String)"),
				refusedAlone(DataBindContext::builder, ConstantBodyMethodParameter.class,
						ConstantBodyMethodParameter.class.getName() + " is an enum, written as its constant's name, so "
								+ "nothing reads @Unbound on parameter 0 of the method code(String) of the body of its "
								+ "constant RED"),
				refusedAlone(DataBindContext::builder, TupleMethodParameter.class, tuple(TupleMethodParameter.class,
						"@Field", "parameter 0 of the method TupleMethodParameter.with(String)")));
	}

	@Test
	void aProtocolRefusesEveryBindingAnnotationOnItsMethods() {
		assertAll(
				() -> assertEquals("refused: " + MethodAnnotatedProtocol.class.getName() + " is a protocol, read as "
						+ "its exchanges, so nothing reads @Field on the method MethodAnnotatedProtocol.ask(Ask)",
						protocol(MethodAnnotatedProtocol.class)),
				() -> assertEquals("refused: " + ParameterAnnotatedProtocol.class.getName() + " is a protocol, read "
						+ "as its exchanges, so nothing reads @Union on parameter 0 of the method "
						+ "ParameterAnnotatedProtocol.ask(Ask)", protocol(ParameterAnnotatedProtocol.class)));
	}

	private static String protocol(Class<?> type) {
		try {
			PLAIN.getProtocol(type);
			return "described";
		} catch (Exception e) {
			return "refused: " + e.getMessage();
		}
	}

	private static void roundTrip(Object value, Map<String, Object> map, Object[] array) throws Throwable {
		MapMapper maps = new MapMapper(PLAIN);
		assertEquals(map, maps.toMap(value));
		assertEquals(value, maps.toObject(value.getClass(), map));
		ArrayMapper arrays = new ArrayMapper(PLAIN);
		assertArrayEquals(array, arrays.toArray(value));
		assertEquals(value, arrays.toObject(value.getClass(), array));
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

	/** In two fresh contexts, {@code message} is the whole of the refusal's message chain. */
	private static Executable refusedAlone(Supplier<DataBindContext.Builder> builder, Class<?> type, String message) {
		return () -> assertAll(Stream.of(1, 2).<Executable>map(run -> () -> assertEquals(List.of(message),
				messages(builder.get().build(), type), type.getSimpleName() + ", context " + run)));
	}

	private static Executable refusedAt(DataParameterizedType at, String message) {
		Class<?> type = (Class<?>) at.getRawType();
		return () -> assertEquals(message, assertThrows(DataBindException.class, () -> PLAIN.getDescriptor(type, at))
				.getMessage(), type.getSimpleName());
	}

	private static List<String> messages(DataBindContext context, Class<?> type) {
		List<String> messages = new ArrayList<>();
		try {
			context.getDescriptor(type);
		} catch (Exception e) {
			for (Throwable at = e; at != null; at = at.getCause()) {
				messages.add(at.getMessage());
			}
		}
		return messages;
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
