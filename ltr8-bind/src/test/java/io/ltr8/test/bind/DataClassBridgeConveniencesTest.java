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
import io.ltr8.bind.DataClassBridge;
import io.ltr8.test.data.ThrowingBridges;
import io.ltr8.test.data.ThrowingBridges.Throwing;
import io.ltr8.test.data.ToDataBridges.Problem;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;
import io.ltr8.test.data.UUIDBridge;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code toData(Object)} and {@code toObject(Object)} call the bridge's two handles: an unchecked throwable passes
 * through as itself and a checked one arrives in an {@link UndeclaredThrowableException}.
 */
class DataClassBridgeConveniencesTest {

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void aToDataBridgeConvertsBothWays() throws DataBindException {
		DataClassBridge bridge = bridge(SkuNotFoundException.class);

		Object record = bridge.toData(new SkuNotFoundException(new SkuNotFound("A-1")));
		assertEquals(new SkuNotFound("A-1"), record);
		assertEquals("A-1", assertInstanceOf(SkuNotFoundException.class, bridge.toObject(record)).sku());
	}

	@Test
	void aRegisteredAtomBridgeConvertsBothWays() throws DataBindException {
		DataBindContext withUuid = DataBindContext.builder().registerAtom(UUID.class, new UUIDBridge()).build();
		DataClassBridge bridge = withUuid.getDescriptor(UUID.class).bridge().orElseThrow();
		UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

		assertEquals(id.toString(), bridge.toData(id));
		assertEquals(id, bridge.toObject(id.toString()));
	}

	@Test
	void anArgumentOfTheWrongClassIsAClassCastException() throws DataBindException {
		DataClassBridge bridge = bridge(SkuNotFoundException.class);

		assertThrows(ClassCastException.class, () -> bridge.toData(new SkuNotFound("A-1")));
		assertThrows(ClassCastException.class, () -> bridge.toObject(new Problem("p", 1)));
	}

	@Test
	void anUncheckedExceptionFromTheConversionPassesThroughAsItself() throws DataBindException {
		DataClassBridge bridge = bridge(Throwing.class);
		Throwing throwing = (Throwing) bridge.toObject(new Problem("runtime", 2));

		assertSame(ThrowingBridges.RUNTIME, assertThrows(IllegalStateException.class, () -> bridge.toData(throwing)));
		assertSame(ThrowingBridges.RUNTIME,
				assertThrows(IllegalStateException.class, () -> bridge.toObject(new Problem("ok", 1))));
	}

	@Test
	void anUncheckedExceptionFromARegisteredAtomBridgePassesThroughAsItself() throws DataBindException {
		DataBindContext withUuid = DataBindContext.builder().registerAtom(UUID.class, new UUIDBridge()).build();
		DataClassBridge bridge = withUuid.getDescriptor(UUID.class).bridge().orElseThrow();

		assertThrows(IllegalArgumentException.class, () -> bridge.toObject("not a uuid"));
	}

	@Test
	void anErrorFromTheConversionPassesThroughAsItself() throws DataBindException {
		DataClassBridge bridge = bridge(Throwing.class);
		Throwing throwing = (Throwing) bridge.toObject(new Problem("error", 2));

		assertSame(ThrowingBridges.ERROR, assertThrows(AssertionError.class, () -> bridge.toData(throwing)));
	}

	@Test
	void aCheckedExceptionFromTheConversionArrivesAsTheCauseOfAnUndeclaredThrowableException()
			throws DataBindException {
		DataClassBridge bridge = bridge(Throwing.class);
		Throwing sneaky = (Throwing) bridge.toObject(new Problem("checked", 2));

		UndeclaredThrowableException fromConstructor = assertThrows(UndeclaredThrowableException.class,
				() -> bridge.toObject(new Problem("declared", 0)));
		assertSame(ThrowingBridges.CHECKED, fromConstructor.getCause());
		assertInstanceOf(IOException.class, fromConstructor.getUndeclaredThrowable());

		UndeclaredThrowableException fromToData = assertThrows(UndeclaredThrowableException.class,
				() -> bridge.toData(sneaky));
		assertSame(ThrowingBridges.CHECKED, fromToData.getCause());
	}

	private DataClassBridge bridge(Class<?> type) throws DataBindException {
		return context.getDescriptor(type).bridge().orElseThrow();
	}
}
