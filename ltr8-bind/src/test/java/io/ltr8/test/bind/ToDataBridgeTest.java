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

import io.ltr8.annotation.ToData;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.ToDataBridges.Carrier;
import io.ltr8.test.data.ToDataBridges.ConstructorTakesSubtype;
import io.ltr8.test.data.ToDataBridges.ConstructorTakesSupertype;
import io.ltr8.test.data.ToDataBridges.InheritsFromBase;
import io.ltr8.test.data.ToDataBridges.InheritsFromInterface;
import io.ltr8.test.data.ToDataBridges.InheritsThroughTypeArgument;
import io.ltr8.test.data.ToDataBridges.InheritsUnboundTypeVariable;
import io.ltr8.test.data.ToDataBridges.InheritsWithoutConstructor;
import io.ltr8.test.data.ToDataBridges.OutOfStock;
import io.ltr8.test.data.ToDataBridges.ParameterizedRecordType;
import io.ltr8.test.data.ToDataBridges.Payload;
import io.ltr8.test.data.ToDataBridges.Problem;
import io.ltr8.test.data.ToDataBridges.ProblemBase;
import io.ltr8.test.data.ToDataBridges.ProtectedConstructor;
import io.ltr8.test.data.ToDataBridges.RawToData;
import io.ltr8.test.data.ToDataBridges.RedeclaresToData;
import io.ltr8.test.data.ToDataBridges.SkuError;
import io.ltr8.test.data.ToDataBridges.SkuMissing;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;
import io.ltr8.test.data.ToDataBridges.SkuShort;
import io.ltr8.test.data.ToDataBridges.TypeVariableRecordType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class implementing {@link ToData}{@code <S>} is described by its record {@code S} through a
 * {@link DataClassBridge}, and only when it declares {@code ToData<S>} itself and has a public constructor taking
 * exactly {@code S}. Every other way of reaching {@code ToData} is refused with a message naming what is wrong.
 */
class ToDataBridgeTest {

	private final DataBindContext context = DataBindContext.builder().build();

	// --- bridged

	@Test
	void anExceptionDeclaringToDataIsDescribedByItsRecord() throws Throwable {
		DataClassRecord descriptor = bridged(SkuNotFoundException.class, SkuNotFound.class);
		DataClassBridge bridge = descriptor.bridge().orElseThrow();

		SkuNotFound record = new SkuNotFound("A-1");
		Object rebuilt = bridge.toObject().invokeWithArguments(record);
		SkuNotFoundException exception = assertInstanceOf(SkuNotFoundException.class, rebuilt);
		assertEquals("A-1", exception.sku());
		assertEquals(record, bridge.toData().invokeWithArguments(exception));
	}

	@Test
	void anExceptionDeclaringToDataRoundTripsThroughTheMappers() throws Throwable {
		SkuNotFoundException exception = new SkuNotFoundException(new SkuNotFound("A-1"));

		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(exception);
		assertEquals(Map.of("sku", "A-1"), map);
		SkuNotFoundException fromMap = assertInstanceOf(SkuNotFoundException.class,
				mapMapper.toObject(SkuNotFoundException.class, map));
		assertEquals("A-1", fromMap.sku());

		ArrayMapper arrayMapper = new ArrayMapper(context);
		Object[] values = arrayMapper.toArray(exception);
		assertEquals(Arrays.asList("A-1"), Arrays.asList(values));
		assertEquals("A-1", arrayMapper.toObject(SkuNotFoundException.class, values).sku());
	}

	@Test
	void subclassesOfABaseWithoutToDataEachBridgeToTheirOwnRecord() throws Throwable {
		bridged(SkuMissing.class, SkuNotFound.class);
		bridged(SkuShort.class, OutOfStock.class);

		MapMapper mapMapper = new MapMapper(context);
		SkuShort shortOf = new SkuShort(new OutOfStock("B-2", 3));
		Map<String, Object> map = mapMapper.toMap(shortOf);
		assertEquals(Map.of("sku", "B-2", "available", 3), map);
		SkuShort rebuilt = assertInstanceOf(SkuShort.class, mapMapper.toObject(SkuShort.class, map));
		assertEquals(3, rebuilt.available());
		assertEquals(Map.of("sku", "C-3"), mapMapper.toMap(new SkuMissing(new SkuNotFound("C-3"))));
	}

	@Test
	void aSubclassRedeclaringItsBasesToDataBridges() throws Throwable {
		bridged(RedeclaresToData.class, Problem.class);
	}

	// --- refused: ToData reached other than by the class's own declaration

	@Test
	void aSubclassInheritingToDataFromItsBaseIsToldToDeclareIt() {
		String message = refusal(InheritsFromBase.class);
		assertNames(message, "inherits ToData from", InheritsFromBase.class.getName(), ProblemBase.class.getName(),
				"implements ToData<" + Problem.class.getName() + ">");
		assertFalse(message.contains("constructor"), message);
	}

	@Test
	void aClassInheritingToDataThroughAnInterfaceIsToldToDeclareIt() {
		assertNames(refusal(InheritsFromInterface.class), "inherits ToData from",
				InheritsFromInterface.class.getName(), SkuError.class.getName(),
				"implements ToData<" + SkuNotFound.class.getName() + ">");
	}

	@Test
	void aClassInheritingToDataThroughATypeArgumentIsToldToDeclareIt() {
		assertNames(refusal(InheritsThroughTypeArgument.class), "inherits ToData from",
				InheritsThroughTypeArgument.class.getName(), Carrier.class.getName(), "implements ToData<S>",
				"S its record class");
	}

	@Test
	void aClassInheritingToDataWithoutAConstructorIsToldToDeclareIt() {
		assertNames(refusal(InheritsWithoutConstructor.class), "inherits ToData from",
				InheritsWithoutConstructor.class.getName(), ProblemBase.class.getName(),
				"implements ToData<" + Problem.class.getName() + ">");
	}

	@Test
	void anInheritedRecordTypeLeftATypeVariableIsReportedWithoutNamingARecord() {
		String message = refusal(InheritsUnboundTypeVariable.class);
		assertNames(message, "inherits ToData from", InheritsUnboundTypeVariable.class.getName(),
				Carrier.class.getName(), "implements ToData<S>", "S its record class");
		assertFalse(message.contains(SkuNotFound.class.getName()), message);
		assertFalse(message.contains(Problem.class.getName()), message);
	}

	// --- refused: a declaration that names no record class

	@Test
	void aRawToDataIsRefused() {
		assertNames(refusal(RawToData.class), RawToData.class.getName(), "raw");
	}

	@Test
	void aParameterizedRecordTypeIsRefused() {
		assertNames(refusal(ParameterizedRecordType.class), ParameterizedRecordType.class.getName(),
				"ToData<java.util.List<java.lang.String>>", "parameterized type", "not a class");
	}

	@Test
	void aTypeVariableRecordTypeIsRefused() {
		assertAll(
				() -> assertNames(refusal(TypeVariableRecordType.class), TypeVariableRecordType.class.getName(),
						"ToData<T>", "type variable", "not a class"),
				() -> assertNames(refusal(Carrier.class), Carrier.class.getName(), "ToData<S>", "type variable",
						"not a class"));
	}

	// --- refused: no way to rebuild the class from its record

	@Test
	void anInterfaceDeclaringToDataIsRefused() {
		assertNames(refusal(SkuError.class), SkuError.class.getName(), "is an interface",
				SkuNotFound.class.getName());
	}

	@Test
	void aConstructorTakingTheRecordThatIsNotPublicIsRefused() {
		assertNames(refusal(ProtectedConstructor.class), ProtectedConstructor.class.getName(),
				"no public constructor taking " + Problem.class.getName());
	}

	@Test
	void aConstructorTakingASupertypeOfTheRecordIsRefused() {
		assertNames(refusal(ConstructorTakesSupertype.class), ConstructorTakesSupertype.class.getName(),
				"no public constructor taking " + SkuNotFound.class.getName());
	}

	@Test
	void aConstructorTakingASubtypeOfTheRecordIsRefused() {
		assertNames(refusal(ConstructorTakesSubtype.class), ConstructorTakesSubtype.class.getName(),
				"no public constructor taking " + Payload.class.getName());
	}

	@Test
	void aClassThePublicLookupCannotReachIsRefused() {
		assertNames(refusal(NotPublic.class), NotPublic.class.getName(), "public");
	}

	private static class NotPublic implements ToData<Problem> {

		public NotPublic(Problem problem) {
		}

		@Override
		public Problem toData() {
			return new Problem("hidden", 500);
		}
	}

	private DataClassRecord bridged(Class<?> type, Class<?> record) throws DataBindException {
		DataClass descriptor = context.getDescriptor(type);
		DataClassRecord bridged = assertInstanceOf(DataClassRecord.class, descriptor);
		assertEquals(type, bridged.typeClass());
		assertEquals(record, bridged.dataClass());
		assertTrue(bridged.bridge().isPresent());

		DataClassRecord ofRecord = assertInstanceOf(DataClassRecord.class, context.getDescriptor(record));
		assertEquals(fieldShapes(ofRecord.fields()), fieldShapes(bridged.fields()));
		return bridged;
	}

	private static List<String> fieldShapes(DataClassField[] fields) {
		return Arrays.stream(fields).map(field -> field.name() + ":" + field.type().getName()).toList();
	}

	/** Every message on the refusal's cause chain, since {@code resolveRecord} wraps the one that says why. */
	private String refusal(Class<?> type) {
		DataBindException thrown = assertThrows(DataBindException.class, () -> context.getDescriptor(type));
		StringBuilder messages = new StringBuilder();
		for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
			messages.append(cause.getMessage()).append('\n');
		}
		return messages.toString();
	}

	private static void assertNames(String message, String... facts) {
		for (String fact : facts) {
			assertTrue(message.contains(fact), () -> "expected '" + fact + "' in:\n" + message);
		}
	}
}
