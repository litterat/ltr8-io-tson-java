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
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.DataExchange;
import io.ltr8.test.data.Protocols.ArchivedOrderException;
import io.ltr8.test.data.Protocols.Catalogue;
import io.ltr8.test.data.Protocols.OrderNotFound;
import io.ltr8.test.data.Protocols.OrderNotFoundException;
import io.ltr8.test.data.Protocols.Orders;
import io.ltr8.test.data.Protocols.PaymentDeclined;
import io.ltr8.test.data.Protocols.PaymentDeclinedException;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code exchange.error(thrown)} says which declared error a throwable is: the very {@link DataClass} {@code
 * errors()} lists, whose bridge turns the throwable into its record and back.
 */
class ExchangeErrorTest {

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void aDeclaredErrorIsTheInstanceErrorsLists() throws DataBindException {
		DataExchange charge = exchange(Catalogue.class, "charge");

		assertSame(charge.errors().get(0), charge.error(new PaymentDeclinedException(new PaymentDeclined("card")))
				.orElseThrow());
		assertSame(charge.errors().get(1), charge.error(new OrderNotFoundException(new OrderNotFound("1")))
				.orElseThrow());
		assertSame(charge.errors().get(2), charge.error(new SkuNotFoundException(new SkuNotFound("A")))
				.orElseThrow());
	}

	@Test
	void aSubclassInstanceOfADeclaredErrorIsThatError() throws DataBindException {
		DataExchange getOrder = exchange(Orders.class, "getOrder");

		DataClass error = getOrder.error(new ArchivedOrderException(new OrderNotFound("old"))).orElseThrow();
		assertSame(getOrder.errors().getFirst(), error);
		assertSame(OrderNotFoundException.class, error.typeClass());
	}

	@Test
	void aThrowableTheExchangeDoesNotDeclareIsEmpty() throws DataBindException {
		DataExchange placeOrder = exchange(Orders.class, "placeOrder");

		assertTrue(placeOrder.error(new IllegalStateException("a bug")).isEmpty());
		assertTrue(placeOrder.error(new AssertionError("an error")).isEmpty());
		assertTrue(placeOrder.error(new IOException("checked")).isEmpty());
		assertTrue(placeOrder.error(new OrderNotFoundException(new OrderNotFound("1"))).isEmpty(),
				"declared by another exchange, not this one");
	}

	@Test
	void nullIsEmpty() throws DataBindException {
		assertTrue(exchange(Orders.class, "placeOrder").error(null).isEmpty());
	}

	@Test
	void anExchangeWithNoErrorsAnswersEmptyForEverything() throws DataBindException {
		DataExchange listOrders = exchange(Orders.class, "listOrders");

		assertTrue(listOrders.error(new SkuNotFoundException(new SkuNotFound("A"))).isEmpty());
	}

	@Test
	void theErrorsBridgeTurnsTheThrowableIntoItsRecordAndBack() throws DataBindException {
		DataExchange placeOrder = exchange(Orders.class, "placeOrder");
		SkuNotFoundException thrown = new SkuNotFoundException(new SkuNotFound("A-9"));

		DataClassBridge bridge = placeOrder.error(thrown).orElseThrow().bridge().orElseThrow();
		Object record = bridge.toData(thrown);
		assertEquals(new SkuNotFound("A-9"), record);

		SkuNotFoundException rebuilt = assertInstanceOf(SkuNotFoundException.class, bridge.toObject(record));
		assertEquals("A-9", rebuilt.sku());
	}

	@Test
	void aSubclassInstanceIsTurnedIntoTheDeclaredErrorsRecord() throws DataBindException {
		DataExchange getOrder = exchange(Orders.class, "getOrder");
		ArchivedOrderException thrown = new ArchivedOrderException(new OrderNotFound("old"));

		DataClassBridge bridge = getOrder.error(thrown).orElseThrow().bridge().orElseThrow();
		assertEquals(new OrderNotFound("old"), bridge.toData(thrown));
	}

	private DataExchange exchange(Class<?> type, String name) throws DataBindException {
		return context.getProtocol(type).exchange(name).orElseThrow();
	}
}
