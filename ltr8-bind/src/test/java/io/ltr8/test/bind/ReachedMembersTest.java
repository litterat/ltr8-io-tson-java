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
import io.ltr8.bind.analysis.CodeAnalysisException;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.ReachedMembers;
import io.ltr8.test.data.ReachedMembers.HidesHiddenFinalField;
import io.ltr8.test.data.ReachedMembers.HidesHiddenFinalFieldByType;
import io.ltr8.test.data.ReachedMembers.HidesHiddenMutableField;
import io.ltr8.test.data.ReachedMembers.HidesHiddenMutableFieldByType;
import io.ltr8.test.data.ReachedMembers.HidesPublicFinalField;
import io.ltr8.test.data.ReachedMembers.HidesPublicMutableField;
import io.ltr8.test.data.ReachedMembers.InheritsOtherTypedMutableShadow;
import io.ltr8.test.data.ReachedMembers.InheritsOtherTypedShadow;
import io.ltr8.test.data.ReachedMembers.PublicMutableNameBase;
import io.ltr8.test.data.ReachedMembers.ReadsHiddenSetter;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * How {@code ImmutableFinder} and {@code GetSetFinder} reach a member through the described class: a member of a
 * super class that is not public, a field another of its name hides, and a member reached through no public class.
 * Both finders reach a member by one rule, so each situation is held through each.
 */
class ReachedMembersTest {

	private final DataBindContext context = DataBindContext.builder().build();

	// --- read through the constructor's analysis

	/** The message is the same on every run: the lookup's own text, which names an array by identity, is the cause. */
	@Test
	void anAccessorOfAClassThatIsNotPublicIsRefusedNamingTheClassAndAccessor() {
		assertAll(Stream.of("HiddenWithAccessor", "HiddenWithNamedAccessor").map(name -> () -> {
			Class<?> type = fixture(name);
			assertUnreachable(type, "method " + type.getName() + ".name");
		}));
	}

	@Test
	void aConstructorFieldOfAClassThatIsNotPublicInheritedFromAnotherIsRefusedNamingItsDeclarer() throws Exception {
		Class<?> type = fixture("HiddenInheritsHiddenField");
		assertUnreachable(type, "field " + type.getSuperclass().getName() + ".name");
	}

	/** The name and type resolve past the field between, as the JVM resolves them. */
	@Test
	void aConstructorFieldBesideAFieldOfAnotherTypeBetweenIsReadFromTheFieldItNames() throws Throwable {
		InheritsOtherTypedShadow value = new InheritsOtherTypedShadow("v");
		DataClassRecord record = record(InheritsOtherTypedShadow.class);
		assertEquals(List.of("name:String"), shape(record));
		assertEquals("v", record.fields()[0].get(value));
		for (Object copy : roundTrips(value)) {
			assertEquals("v", record.fields()[0].get(copy));
		}
	}

	@Test
	void aConstructorFieldOfASuperClassThatIsNotPublicBesideAPrivateFieldOfAnotherTypeIsRead() throws Throwable {
		HidesHiddenFinalFieldByType value = new HidesHiddenFinalFieldByType("v");
		DataClassRecord record = record(HidesHiddenFinalFieldByType.class);
		assertEquals(List.of("name:String"), shape(record));
		assertEquals("v", record.fields()[0].get(value));
		for (Object copy : roundTrips(value)) {
			assertEquals("v", record.fields()[0].get(copy));
		}
	}

	@Test
	void aConstructorFieldOfASuperClassThatIsNotPublicHiddenByAPrivateFieldIsRefusedAsHidden() {
		assertHidden(HidesHiddenFinalField.class);
	}

	@Test
	void aConstructorFieldOfAPublicSuperClassHiddenByAPrivateFieldIsReadFromTheSuperClassField() throws Throwable {
		HidesPublicFinalField value = new HidesPublicFinalField("v");
		DataClassRecord record = record(HidesPublicFinalField.class);
		assertEquals(List.of("name:String"), shape(record));
		assertEquals("v", record.fields()[0].get(value));
		roundTrips(value);
	}

	// --- read and written as a public field or a pair

	@Test
	void aGetterPairsWithTheSetterASuperClassThatIsNotPublicDeclares() throws Throwable {
		DataClassRecord record = record(ReadsHiddenSetter.class);
		assertEquals(List.of("value:String set"), shape(record));
		ReadsHiddenSetter written = new ReadsHiddenSetter();
		record.fields()[0].set(written, "buoy");
		assertEquals("buoy", written.getValue());
		ReadsHiddenSetter value = new ReadsHiddenSetter();
		value.setValue("anchor");
		for (Object copy : roundTrips(value)) {
			assertEquals("anchor", ((ReadsHiddenSetter) copy).getValue());
		}
	}

	@Test
	void aPairOfAClassThatIsNotPublicInheritedFromAnotherIsRefusedNamingItsDeclarer() throws Exception {
		Class<?> type = fixture("HiddenInheritsHiddenPair");
		assertUnreachable(type, "method " + type.getSuperclass().getName() + ".getValue");
	}

	/** The described class resolves the name and type to its own private field, so the field is reached nowhere. */
	@Test
	void aPublicFieldOfASuperClassThatIsNotPublicHiddenByAPrivateFieldIsRefusedAsHidden() {
		assertHidden(HidesHiddenMutableField.class);
	}

	/** The name and type resolve past the field between, as the JVM resolves them. */
	@Test
	void aPublicFieldBesideAFieldOfAnotherTypeBetweenIsReadAndWritten() throws Throwable {
		DataClassRecord record = record(InheritsOtherTypedMutableShadow.class);
		assertEquals(List.of("name:String set"), shape(record));
		InheritsOtherTypedMutableShadow value = new InheritsOtherTypedMutableShadow();
		record.fields()[0].set(value, "buoy");
		assertEquals("buoy", record.fields()[0].get(value));
		for (Object copy : roundTrips(value)) {
			assertEquals("buoy", record.fields()[0].get(copy));
		}
	}

	/** The described class resolves a name and type, and a field of another type does not take the name. */
	@Test
	void aPublicFieldOfASuperClassThatIsNotPublicBesideAPrivateFieldOfAnotherTypeIsReadAndWritten() throws Throwable {
		DataClassRecord record = record(HidesHiddenMutableFieldByType.class);
		assertEquals(List.of("name:String set"), shape(record));
		HidesHiddenMutableFieldByType value = new HidesHiddenMutableFieldByType();
		record.fields()[0].set(value, "buoy");
		assertEquals("buoy", record.fields()[0].get(value));
		assertEquals(Map.of("name", "buoy"), new MapMapper(context).toMap(value));
	}

	@Test
	void aPublicFieldOfAPublicSuperClassHiddenByAPrivateFieldIsReadAndWrittenThroughTheSuperClassField()
			throws Throwable {
		DataClassRecord record = record(HidesPublicMutableField.class);
		assertEquals(List.of("name:String set"), shape(record));
		HidesPublicMutableField value = new HidesPublicMutableField();
		record.fields()[0].set(value, "buoy");
		assertEquals("buoy", ((PublicMutableNameBase) value).name);
		assertEquals("buoy", record.fields()[0].get(value));
		for (Object copy : roundTrips(value)) {
			assertEquals("buoy", ((PublicMutableNameBase) copy).name);
		}
	}

	// --- helpers

	private void assertUnreachable(Class<?> type, String member) {
		CodeAnalysisException refused = assertThrows(CodeAnalysisException.class, () -> context.getDescriptor(type),
				type.getName());
		assertEquals(type.getName() + " is bound through a member that is not accessible: " + member
				+ ", through no class the public lookup can access", refused.getMessage());
		assertInstanceOf(IllegalAccessException.class, refused.getCause(), type.getName());
	}

	/** {@code type}'s own private field hides the public field of the same name its super class declares. */
	private void assertHidden(Class<?> type) {
		CodeAnalysisException refused = assertThrows(CodeAnalysisException.class, () -> context.getDescriptor(type));
		assertEquals(type.getName() + " is bound through field " + type.getSuperclass().getName()
				+ ".name, which a field of " + type.getName() + " hides from the public lookup", refused.getMessage());
		assertNull(refused.getCause());
	}

	/** A fixture class that is not public, so this package cannot name it. */
	private static Class<?> fixture(String simpleName) throws ClassNotFoundException {
		return Class.forName(ReachedMembers.class.getName() + "$" + simpleName);
	}

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

	/** {@code value} written and read back through an {@link ArrayMapper}, then through a {@link MapMapper}. */
	private List<Object> roundTrips(Object value) throws Throwable {
		Class<?> type = value.getClass();
		ArrayMapper array = new ArrayMapper(context);
		MapMapper map = new MapMapper(context);
		return List.of(array.toObject(type, array.toArray(value)), map.toObject(type, map.toMap(value)));
	}
}
