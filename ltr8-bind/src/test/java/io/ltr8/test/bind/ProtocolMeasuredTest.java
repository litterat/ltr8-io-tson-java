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
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.test.data.Protocols.NewOrder;
import io.ltr8.test.data.Protocols.OrderPage;
import io.ltr8.test.data.Protocols.Orders;
import io.ltr8.test.data.RefusedProtocols.InheritedProblemException;
import io.ltr8.test.data.RefusedProtocols.ProblemException;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The facts under the proposal's "Measured" that the descriptors rest on and that no other test class here holds,
 * each through bind's own API. The invoker's rows are {@code ExchangeInvokerTest}'s, the proxy rows {@code
 * ProxyOverHandlerTest}'s and the named-module row {@code ProtocolNamedModuleTest}'s.
 */
class ProtocolMeasuredTest {

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void aThrowableImplementingToDataIsARecordDescriptorWithABridgeBothWays() throws Throwable {
		DataClassRecord descriptor = assertInstanceOf(DataClassRecord.class,
				context.getDescriptor(SkuNotFoundException.class));
		DataClassBridge bridge = descriptor.bridge().orElseThrow();

		assertEquals(SkuNotFoundException.class, descriptor.typeClass());
		assertEquals(SkuNotFound.class, descriptor.dataClass());
		assertEquals(MethodType.methodType(SkuNotFound.class, SkuNotFoundException.class), bridge.toData().type());
		assertEquals(MethodType.methodType(SkuNotFoundException.class, SkuNotFound.class), bridge.toObject().type());

		Object record = bridge.toData().invoke(new SkuNotFoundException(new SkuNotFound("A")));
		Object back = bridge.toObject().invoke(record);
		assertEquals(new SkuNotFound("A"), record);
		assertEquals(new SkuNotFound("A"), assertInstanceOf(SkuNotFoundException.class, back).toData());
	}

	@Test
	void aThrowableInheritingToDataFromABaseExceptionIsRefused() throws DataBindException {
		assertInstanceOf(DataClassRecord.class, context.getDescriptor(ProblemException.class));

		DataBindException refused = assertThrows(DataBindException.class,
				() -> context.getDescriptor(InheritedProblemException.class));
		assertTrue(chain(refused).contains("inherits ToData from " + ProblemException.class.getName()),
				chain(refused));
	}

	@Test
	void aRecordsConstructorHandleTakesAnObjectArray() throws Throwable {
		DataClassRecord newOrder = assertInstanceOf(DataClassRecord.class, context.getDescriptor(NewOrder.class));

		assertEquals(MethodType.methodType(NewOrder.class, Object[].class), newOrder.constructor().type());
		assertEquals(new NewOrder("A", 2), newOrder.constructor().invoke(new Object[] {"A", 2}));
	}

	@Test
	void anInterfaceMethodsParameterHasNoNameWithoutTheParametersFlag() throws Exception {
		// This build does not pass -parameters, so the request is found by its class and never by its name.
		Parameter parameter = Orders.class.getMethod("placeOrder", NewOrder.class).getParameters()[0];

		assertFalse(parameter.isNamePresent());
		assertEquals("arg0", parameter.getName());
		assertTrue(context.getProtocol(Orders.class).exchange("placeOrder").orElseThrow().request().isPresent());
	}

	@Test
	void anOptionalHasNoDescriptorOutsideARecord() throws Exception {
		Type optionalOfString = Spread.class.getMethod("list", Optional.class).getGenericParameterTypes()[0];

		assertThrows(DataBindException.class, () -> context.getDescriptor(Optional.class, optionalOfString));
	}

	@Test
	void aListOfRecordsDescribesAsAnArray() throws Exception {
		Type items = OrderPage.class.getRecordComponents()[0].getGenericType();

		assertInstanceOf(DataClassArray.class, context.getDescriptor(List.class, items));
	}

	public interface Spread {

		void list(Optional<String> status);
	}

	private static String chain(Throwable thrown) {
		StringBuilder messages = new StringBuilder();
		for (Throwable at = thrown; at != null; at = at.getCause()) {
			messages.append(at.getMessage()).append('\n');
		}
		return messages.toString();
	}
}
