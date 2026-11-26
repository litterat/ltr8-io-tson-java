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
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.GetSetClasses.FinalFieldBesideMutableField;
import io.ltr8.test.data.GetSetClasses.FinalFieldWithAccessor;
import io.ltr8.test.data.GetSetClasses.FinalFieldWithoutAccessor;
import io.ltr8.test.data.GetSetClasses.InlineFinalField;
import io.ltr8.test.data.GetSetClasses.MutableFieldBoundByConstructor;
import io.ltr8.test.data.GetSetClasses.MutableFields;
import io.ltr8.test.data.GetSetClasses.StaticField;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which public fields and getter/setter pairs of a hand-written {@code @Record} class are components, and how each
 * is read and written.
 */
class GetSetFinderTest {

	private final DataBindContext context = DataBindContext.builder().build();

	// --- public fields

	@Test
	void aPublicFinalFieldTheConstructorBindsIsReadThroughItsAccessorAndWrittenThroughTheConstructor()
			throws Throwable {
		DataClassRecord record = record(FinalFieldWithAccessor.class);
		assertEquals(List.of("name:String"), shape(record));
		assertReadThroughMethod(record.fields()[0], "name");
		FinalFieldWithAccessor value = new FinalFieldWithAccessor("anchor");
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (FinalFieldWithAccessor copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
		}
	}

	@Test
	void aPublicFinalFieldTheConstructorBindsSitsBesideAPublicMutableField() throws Throwable {
		DataClassRecord record = record(FinalFieldBesideMutableField.class);
		assertEquals(List.of("name:String", "count:int set"), shape(record));
		FinalFieldBesideMutableField value = new FinalFieldBesideMutableField("anchor");
		value.count = 3;
		assertEquals(List.of("anchor", 3), Arrays.asList(new ArrayMapper(context).toArray(value)));
		for (FinalFieldBesideMutableField copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals(3, copy.count);
		}
	}

	@Test
	void aPublicFinalFieldTheConstructorBindsWithoutAnAccessorIsRefusedByTheConstructorAnalysis() {
		assertEquals("Failed to match immutable field accessor for class: " + FinalFieldWithoutAccessor.class
				+ ". Add @Field annotations to assist field 'name'", message(FinalFieldWithoutAccessor.class));
	}

	@Test
	void aPublicFinalFieldNoConstructorBindsIsNoComponent() throws Throwable {
		DataClassRecord record = record(InlineFinalField.class);
		assertEquals(List.of("name:String set"), shape(record));
		InlineFinalField value = new InlineFinalField();
		value.name = "anchor";
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
		for (InlineFinalField copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals("fixed", copy.fixed);
		}
	}

	@Test
	void publicMutableFieldsAreReadAndWrittenThroughTheFieldsInNameOrder() throws Throwable {
		DataClassRecord record = record(MutableFields.class);
		assertEquals(List.of("count:int set", "name:String set"), shape(record));
		MutableFields value = new MutableFields();
		value.name = "anchor";
		value.count = 3;
		for (MutableFields copy : roundTrips(value)) {
			assertEquals("anchor", copy.name);
			assertEquals(3, copy.count);
		}
	}

	@Test
	void aPublicMutableFieldTheConstructorBindsIsWrittenThroughTheConstructor() throws Throwable {
		DataClassRecord record = record(MutableFieldBoundByConstructor.class);
		assertEquals(List.of("name:String set"), shape(record));
		for (MutableFieldBoundByConstructor copy : roundTrips(new MutableFieldBoundByConstructor("anchor"))) {
			assertEquals("anchor", copy.name);
		}
	}

	@Test
	void aStaticFieldIsNoComponent() throws Throwable {
		DataClassRecord record = record(StaticField.class);
		assertEquals(List.of("name:String set"), shape(record));
		StaticField value = new StaticField();
		value.name = "anchor";
		assertEquals(Map.of("name", "anchor"), new MapMapper(context).toMap(value));
	}

	// --- helpers

	private DataClassRecord record(Class<?> type) throws DataBindException {
		return assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
	}

	/** Each field as {@code name:Type}, with {@code set} where the descriptor writes it through a setter. */
	private static List<String> shape(DataClassRecord record) {
		return Arrays.stream(record.fields())
				.map(field -> field.name() + ":" + field.type().getSimpleName()
						+ (field.setter().isPresent() ? " set" : ""))
				.toList();
	}

	private static void assertReadThroughMethod(DataClassField field, String method) {
		MethodHandleInfo read = MethodHandles.lookup().revealDirect(field.accessor());
		assertEquals(MethodHandleInfo.REF_invokeVirtual, read.getReferenceKind(), field.name());
		assertEquals(method, read.getName(), field.name());
	}

	/** {@code value} written and read back through an {@link ArrayMapper}, then through a {@link MapMapper}. */
	private <T> List<T> roundTrips(T value) throws Throwable {
		@SuppressWarnings("unchecked")
		Class<T> type = (Class<T>) value.getClass();
		ArrayMapper array = new ArrayMapper(context);
		MapMapper map = new MapMapper(context);
		return List.of(array.toObject(type, array.toArray(value)), type.cast(map.toObject(type, map.toMap(value))));
	}

	private String message(Class<?> type) {
		return refusal(type).getMessage();
	}

	private DataBindException refusal(Class<?> type) {
		DataBindException refused = assertThrows(DataBindException.class, () -> context.getDescriptor(type));
		assertTrue(refused.getMessage() != null, type.getName());
		return refused;
	}
}
