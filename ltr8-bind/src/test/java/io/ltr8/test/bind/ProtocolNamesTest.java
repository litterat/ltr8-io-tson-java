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
import io.ltr8.bind.DataExchange;
import io.ltr8.bind.DataProtocol;
import io.ltr8.test.data.Protocols.Order;
import io.ltr8.test.data.Protocols.OrderRef;
import io.ltr8.test.data.Protocols.Orders;
import io.ltr8.test.data.Protocols.Snake;
import io.ltr8.test.data.Protocols.SnakeService;
import io.ltr8.test.data.Protocols.Underscored;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An exchange is named as its method is, and found by any name that is one with it once each {@code _} is removed
 * and the character after it upper-cased. The function runs from the data's spelling to Java's only.
 */
class ProtocolNamesTest {

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void exchangesAreOrderedByName() throws DataBindException {
		List<String> names = context.getProtocol(Orders.class).exchanges().stream().map(DataExchange::name).toList();

		assertEquals(names.stream().sorted().toList(), names);
		assertEquals(List.of("cancelOrder", "getOrder", "listOrders", "placeOrder"), names);
	}

	@Test
	void anExchangeIsNamedAsItsMethodIsSpelt() throws DataBindException {
		DataProtocol<Snake> snake = context.getProtocol(Snake.class);

		assertEquals(List.of("get_order", "placeOrder"), snake.exchanges().stream().map(DataExchange::name).toList());
	}

	@Test
	void theSnakeAndCamelSpellingsFindTheSameExchangeInstance() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);

		DataExchange camel = orders.exchange("placeOrder").orElseThrow();
		assertSame(camel, orders.exchange("place_order").orElseThrow());
		assertSame(orders.exchanges().get(3), camel);
	}

	@Test
	void aMethodItselfNamedWithAnUnderscoreIsFoundByEitherSpelling() throws Throwable {
		DataProtocol<Snake> snake = context.getProtocol(Snake.class);

		DataExchange getOrder = snake.exchange("get_order").orElseThrow();
		assertSame(getOrder, snake.exchange("getOrder").orElseThrow());
		assertEquals("get_order", getOrder.name());
		assertEquals(new Order("snake 7", 1), snake.handler(new SnakeService()).handle(getOrder, new OrderRef("7")));
	}

	@Test
	void anUpperCaseFirstLetterFindsNeitherSpelling() throws DataBindException {
		assertTrue(context.getProtocol(Orders.class).exchange("PlaceOrder").isEmpty());
		assertTrue(context.getProtocol(Snake.class).exchange("GetOrder").isEmpty());
	}

	@Test
	void onlyTheCharacterAfterAnUnderscoreChangesCase() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);

		assertTrue(orders.exchange("placeorder").isEmpty());
		assertTrue(orders.exchange("PLACE_ORDER").isEmpty());
		assertTrue(orders.exchange("_place_order").isEmpty(), "the leading _ upper-cases the p");
		assertSame(orders.exchange("placeOrder").orElseThrow(), orders.exchange("place__order").orElseThrow());
		assertSame(orders.exchange("placeOrder").orElseThrow(), orders.exchange("placeOrder_").orElseThrow());
	}

	@Test
	void aLeadingOrTrailingUnderscoreInAMethodNameIsReadAsAnyOther() throws DataBindException {
		DataProtocol<Underscored> underscored = context.getProtocol(Underscored.class);

		assertEquals(List.of("_lead", "trail_"), underscored.exchanges().stream().map(DataExchange::name).toList());
		assertSame(underscored.exchange("_lead").orElseThrow(), underscored.exchange("Lead").orElseThrow());
		assertSame(underscored.exchange("trail_").orElseThrow(), underscored.exchange("trail").orElseThrow());
		assertTrue(underscored.exchange("lead").isEmpty());
	}

	@Test
	void anUnknownNameIsEmpty() throws DataBindException {
		assertTrue(context.getProtocol(Orders.class).exchange("deleteOrder").isEmpty());
		assertTrue(context.getProtocol(Orders.class).exchange("").isEmpty());
	}

	@Test
	void aNullNameIsANullPointerException() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);

		assertThrows(NullPointerException.class, () -> orders.exchange(null));
	}
}
