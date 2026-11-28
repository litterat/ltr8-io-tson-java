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
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.BridgedFields.AbstractBaseBridge;
import io.ltr8.test.data.BridgedFields.Constructed;
import io.ltr8.test.data.BridgedFields.DefaultNumberBridge;
import io.ltr8.test.data.BridgedFields.DirectBridge;
import io.ltr8.test.data.BridgedFields.GenericInterfaceFirstBridge;
import io.ltr8.test.data.BridgedFields.GetterSetter;
import io.ltr8.test.data.BridgedFields.HiddenBaseParsedNumberBridge;
import io.ltr8.test.data.BridgedFields.LongWireBridge;
import io.ltr8.test.data.BridgedFields.ParsedNumberBridge;
import io.ltr8.test.data.BridgedFields.PlainInterfaceFirstBridge;
import io.ltr8.test.data.BridgedFields.PublicField;
import io.ltr8.test.data.BridgedFields.RecordedConstructed;
import io.ltr8.test.data.BridgedFields.RecordedGetterSetter;
import io.ltr8.test.data.BridgedFields.RecordedPublicField;
import io.ltr8.test.data.BridgedFields.RecordingNumberAsText;
import io.ltr8.test.data.BridgedFields.SubInterfaceBridge;
import io.ltr8.test.data.BridgedFields.SubclassedBridge;
import io.ltr8.test.data.BridgedFields.TwoHopBridge;
import io.ltr8.test.data.BridgedFields.TwoLevelsParsedNumberBridge;
import io.ltr8.test.data.BridgedFields.TwoRecordedFields;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgedFieldTest {

	private final DataBindContext context = DataBindContext.builder().build();

	private DataClassField countField(Class<?> type) throws DataBindException {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(type));
		assertEquals(1, record.fields().length);
		DataClassField count = record.fields()[0];
		assertEquals("count", count.name());
		assertEquals(String.class, count.type(), "the field carries the bridge's wire type");
		assertEquals(String.class, assertInstanceOf(DataClassAtom.class, count.dataClass()).typeClass());
		assertFalse(count.isRequired());
		return count;
	}

	@Test
	void aBridgedGetterAndSetterPairIsDescribedWithASetterTakingTheWireValue() throws DataBindException {
		DataClassField count = countField(GetterSetter.class);

		assertEquals(MethodType.methodType(void.class, GetterSetter.class, String.class),
				count.setter().orElseThrow().type());
		GetterSetter value = new GetterSetter();
		count.set(value, "7");
		assertEquals(7, value.getCount());
		assertEquals("7", count.get(value));
		assertTrue(count.isPresent(value));
		assertFalse(count.isPresent(new GetterSetter()));
	}

	@Test
	void aBridgedPublicFieldIsDescribedWithASetterTakingTheWireValue() throws DataBindException {
		DataClassField count = countField(PublicField.class);

		assertEquals(MethodType.methodType(void.class, PublicField.class, String.class),
				count.setter().orElseThrow().type());
		PublicField value = new PublicField();
		count.set(value, "7");
		assertEquals(7, value.count);
		assertEquals("7", count.get(value));
	}

	@Test
	void aBridgedConstructorArgumentIsDescribedWithNoSetter() throws DataBindException {
		DataClassField count = countField(Constructed.class);

		assertTrue(count.setter().isEmpty());
		assertEquals("7", count.get(new Constructed(7)));
	}

	@Test
	void aBridgedGetterAndSetterPairRoundTripsThroughBothMappers() throws Throwable {
		GetterSetter value = new GetterSetter();
		value.setCount(42);
		GetterSetter absent = new GetterSetter();

		MapMapper maps = new MapMapper(context);
		assertEquals(Map.of("count", "42"), maps.toMap(value));
		assertEquals(42, ((GetterSetter) maps.toObject(GetterSetter.class, maps.toMap(value))).getCount());
		assertEquals(Map.of(), maps.toMap(absent));
		assertNull(((GetterSetter) maps.toObject(GetterSetter.class, Map.of())).getCount());

		ArrayMapper arrays = new ArrayMapper(context);
		assertArrayEquals(new Object[] { "42" }, arrays.toArray(value));
		assertEquals(42, arrays.toObject(GetterSetter.class, arrays.toArray(value)).getCount());
		assertArrayEquals(new Object[] { null }, arrays.toArray(absent));
		assertNull(arrays.toObject(GetterSetter.class, new Object[] { null }).getCount());
	}

	@Test
	void aBridgedPublicFieldRoundTripsThroughBothMappers() throws Throwable {
		PublicField value = new PublicField();
		value.count = 42;
		PublicField absent = new PublicField();

		MapMapper maps = new MapMapper(context);
		assertEquals(Map.of("count", "42"), maps.toMap(value));
		assertEquals(42, ((PublicField) maps.toObject(PublicField.class, maps.toMap(value))).count);
		assertEquals(Map.of(), maps.toMap(absent));
		assertNull(((PublicField) maps.toObject(PublicField.class, Map.of())).count);

		ArrayMapper arrays = new ArrayMapper(context);
		assertArrayEquals(new Object[] { "42" }, arrays.toArray(value));
		assertEquals(42, arrays.toObject(PublicField.class, arrays.toArray(value)).count);
		assertArrayEquals(new Object[] { null }, arrays.toArray(absent));
		assertNull(arrays.toObject(PublicField.class, new Object[] { null }).count);
	}

	/** Describes {@code value}'s class in a fresh context and round-trips {@code value} through both mappers. */
	private static void describeAndRoundTrip(Object value, int bridgedFields) throws Throwable {
		RecordingNumberAsText.BUILT.clear();
		RecordingNumberAsText.CONVERTED.clear();
		DataBindContext fresh = DataBindContext.builder().build();

		fresh.getDescriptor(value.getClass());
		assertEquals(bridgedFields, RecordingNumberAsText.BUILT.size(), "one bridge per bridged field");
		fresh.getDescriptor(value.getClass());
		assertEquals(bridgedFields, RecordingNumberAsText.BUILT.size(), "and none for a cached descriptor");

		MapMapper maps = new MapMapper(fresh);
		assertEquals(maps.toMap(value), maps.toMap(maps.toObject(value.getClass(), maps.toMap(value))));
		ArrayMapper arrays = new ArrayMapper(fresh);
		assertArrayEquals(arrays.toArray(value), arrays.toArray(arrays.toObject(value.getClass(),
				arrays.toArray(value))));

		assertEquals(bridgedFields, RecordingNumberAsText.BUILT.size(), "reading and writing build none");
		assertEquals(RecordingNumberAsText.BUILT, RecordingNumberAsText.CONVERTED,
				"toData and toObject run on the one bridge built for the field");
	}

	@Test
	void aBridgedGetterAndSetterPairBuildsOneBridgeForBothDirections() throws Throwable {
		RecordedGetterSetter value = new RecordedGetterSetter();
		value.setCount(42);
		describeAndRoundTrip(value, 1);
	}

	@Test
	void aBridgedPublicFieldBuildsOneBridgeForBothDirections() throws Throwable {
		RecordedPublicField value = new RecordedPublicField();
		value.count = 42;
		describeAndRoundTrip(value, 1);
	}

	@Test
	void aBridgedConstructorArgumentBuildsOneBridgeForBothDirections() throws Throwable {
		describeAndRoundTrip(new RecordedConstructed(42), 1);
	}

	@Test
	void eachBridgedFieldBuildsABridgeOfItsOwn() throws Throwable {
		describeAndRoundTrip(new TwoRecordedFields(1, 2), 2);
	}

	@Test
	void eachDescriptionBuildsItsOwnBridge() throws Throwable {
		RecordingNumberAsText.BUILT.clear();
		DataBindContext.builder().build().getDescriptor(RecordedConstructed.class);
		DataBindContext.builder().build().getDescriptor(RecordedConstructed.class);
		assertEquals(2, RecordingNumberAsText.BUILT.size());
	}

	/** {@code type}'s one component, an {@code Integer count} bridged as text, round-tripped through both mappers. */
	private void assertBridgedCountRoundTrips(Class<?> type) throws Throwable {
		countField(type);
		Object value = type.getConstructor(Integer.class).newInstance(42);
		Object absent = type.getConstructor(Integer.class).newInstance((Integer) null);

		MapMapper maps = new MapMapper(context);
		assertEquals(Map.of("count", "42"), maps.toMap(value));
		assertEquals(value, maps.toObject(type, maps.toMap(value)));
		assertEquals(Map.of(), maps.toMap(absent));
		assertEquals(absent, maps.toObject(type, Map.of()));

		ArrayMapper arrays = new ArrayMapper(context);
		assertArrayEquals(new Object[] { "42" }, arrays.toArray(value));
		assertEquals(value, arrays.toObject(type, arrays.toArray(value)));
		assertArrayEquals(new Object[] { null }, arrays.toArray(absent));
		assertEquals(absent, arrays.toObject(type, new Object[] { null }));
	}

	@Test
	void aBridgeImplementingDataBridgeItselfRoundTripsThroughBothMappers() throws Throwable {
		assertBridgedCountRoundTrips(DirectBridge.class);
	}

	@Test
	void aBridgeExtendingAnAbstractGenericBaseRoundTripsThroughBothMappers() throws Throwable {
		assertBridgedCountRoundTrips(AbstractBaseBridge.class);
	}

	@Test
	void aBridgeWhoseTypeArgumentsAreBoundTwoSuperClassesApartRoundTripsThroughBothMappers() throws Throwable {
		assertBridgedCountRoundTrips(TwoHopBridge.class);
	}

	@Test
	void aBridgeExtendingAConcreteBridgeRoundTripsThroughBothMappers() throws Throwable {
		assertBridgedCountRoundTrips(SubclassedBridge.class);
	}

	@Test
	void aBridgeListingAPlainInterfaceBeforeDataBridgeRoundTripsThroughBothMappers() throws Throwable {
		assertBridgedCountRoundTrips(PlainInterfaceFirstBridge.class);
	}

	@Test
	void aBridgeListingAGenericInterfaceBeforeDataBridgeRoundTripsThroughBothMappers() throws Throwable {
		assertBridgedCountRoundTrips(GenericInterfaceFirstBridge.class);
	}

	@Test
	void aBridgeImplementingASubInterfaceOfDataBridgeRoundTripsThroughBothMappers() throws Throwable {
		assertBridgedCountRoundTrips(SubInterfaceBridge.class);
	}

	@Test
	void aBridgeInheritingItsMethodsFromAGenericAbstractClassRoundTripsThroughBothMappers() throws Throwable {
		assertBridgedCountRoundTrips(ParsedNumberBridge.class);
	}

	@Test
	void aBridgeInheritingItsMethodsTwoGenericClassesUpRoundTripsThroughBothMappers() throws Throwable {
		assertBridgedCountRoundTrips(TwoLevelsParsedNumberBridge.class);
	}

	@Test
	void aBridgeInheritingItsMethodsFromAClassThePublicLookupCannotAccessRoundTripsThroughBothMappers()
			throws Throwable {
		assertBridgedCountRoundTrips(HiddenBaseParsedNumberBridge.class);
	}

	@Test
	void aBridgeInheritingItsMethodsAsDefaultMethodsOfAGenericInterfaceRoundTripsThroughBothMappers()
			throws Throwable {
		assertBridgedCountRoundTrips(DefaultNumberBridge.class);
	}

	@Test
	void aBridgeToABoxedPrimitiveInheritingItsMethodsRoundTripsThroughBothMappers() throws Throwable {
		DataClassRecord record = assertInstanceOf(DataClassRecord.class, context.getDescriptor(LongWireBridge.class));
		assertEquals(Long.class, record.fields()[0].type());
		LongWireBridge value = new LongWireBridge(42);

		MapMapper maps = new MapMapper(context);
		assertEquals(Map.of("count", 42L), maps.toMap(value));
		assertEquals(value, maps.toObject(LongWireBridge.class, maps.toMap(value)));

		ArrayMapper arrays = new ArrayMapper(context);
		assertArrayEquals(new Object[] { 42L }, arrays.toArray(value));
		assertEquals(value, arrays.toObject(LongWireBridge.class, arrays.toArray(value)));
	}

	@Test
	void aBridgedConstructorArgumentRoundTripsThroughBothMappers() throws Throwable {
		Constructed value = new Constructed(42);
		Constructed absent = new Constructed(null);

		MapMapper maps = new MapMapper(context);
		assertEquals(Map.of("count", "42"), maps.toMap(value));
		assertEquals(42, ((Constructed) maps.toObject(Constructed.class, maps.toMap(value))).count());
		assertEquals(Map.of(), maps.toMap(absent));
		assertNull(((Constructed) maps.toObject(Constructed.class, Map.of())).count());

		ArrayMapper arrays = new ArrayMapper(context);
		assertArrayEquals(new Object[] { "42" }, arrays.toArray(value));
		assertEquals(42, arrays.toObject(Constructed.class, arrays.toArray(value)).count());
		assertArrayEquals(new Object[] { null }, arrays.toArray(absent));
		assertNull(arrays.toObject(Constructed.class, new Object[] { null }).count());
	}
}
