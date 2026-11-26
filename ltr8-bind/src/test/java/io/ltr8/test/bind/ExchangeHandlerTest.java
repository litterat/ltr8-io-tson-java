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
import io.ltr8.bind.DataExchangeHandler;
import io.ltr8.bind.DataProtocol;
import io.ltr8.test.data.Protocols.NewOrder;
import io.ltr8.test.data.Protocols.Order;
import io.ltr8.test.data.Protocols.OrderPage;
import io.ltr8.test.data.Protocols.OrderQuery;
import io.ltr8.test.data.Protocols.OrderRef;
import io.ltr8.test.data.Protocols.OrderService;
import io.ltr8.test.data.Protocols.Orders;
import io.ltr8.test.data.Protocols.Sealed;
import io.ltr8.test.data.Protocols.SealedService;
import io.ltr8.test.data.Protocols.Snake;
import io.ltr8.test.data.Protocols.Thrower;
import io.ltr8.test.data.Protocols.Throwing;
import io.ltr8.test.data.Protocols.Trigger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.ltr8.test.bind.ProtocolRefusalTest.assertNames;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code handler(target)} stands a Java object in as a {@link DataExchangeHandler}: each exchange reaches its own
 * method, responses come back and throwables go through as themselves. Per-call context travels in a {@link
 * ScopedValue} the caller binds around {@code handle}.
 */
class ExchangeHandlerTest {

	private static final ScopedValue<String> CALLER = ScopedValue.newInstance();

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void eachExchangeIsDispatchedToItsOwnMethod() throws Throwable {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchangeHandler handler = orders.handler(new OrderService());

		assertEquals(new Order("A-1", 2), handler.handle(exchange(orders, "placeOrder"), new NewOrder("A-1", 2)));
		assertEquals(new Order("7", 1), handler.handle(exchange(orders, "getOrder"), new OrderRef("7")));
		assertEquals(new OrderPage(List.of(new Order("open", 1), new Order("open", 2))),
				handler.handle(exchange(orders, "listOrders"), new OrderQuery("open")));
		assertNull(handler.handle(exchange(orders, "cancelOrder"), new OrderRef("7")));
	}

	@Test
	void anExchangeTakingNothingIsAnsweredWhateverRequestItIsHanded() throws Throwable {
		DataProtocol<Throwing> throwing = context.getProtocol(Throwing.class);
		DataExchangeHandler handler = throwing.handler(new Thrower());

		assertEquals(new Order("nothing", 0), handler.handle(exchange(throwing, "nothing"), null));
		assertEquals(new Order("nothing", 0), handler.handle(exchange(throwing, "nothing"), new OrderRef("x")));
	}

	@Test
	void aSealedInterfacesImplementationIsAHandler() throws Throwable {
		DataProtocol<Sealed> sealed = context.getProtocol(Sealed.class);

		assertEquals(new Order("sealed 3", 1),
				sealed.handler(new SealedService()).handle(exchange(sealed, "getOrder"), new OrderRef("3")));
	}

	@Test
	void everyThrowableComesOutAsTheSameInstanceNeverWrapped() throws Throwable {
		DataProtocol<Throwing> throwing = context.getProtocol(Throwing.class);
		DataExchangeHandler handler = throwing.handler(new Thrower());
		DataExchange run = exchange(throwing, "run");

		Map<String, Throwable> triggers = Map.of("declared", Thrower.DECLARED, "runtime", Thrower.RUNTIME, "error",
				Thrower.ERROR, "checked", Thrower.CHECKED);
		for (Map.Entry<String, Throwable> trigger : triggers.entrySet()) {
			String what = trigger.getKey();
			Throwable expected = trigger.getValue();
			Throwable thrown = assertThrows(Throwable.class, () -> handler.handle(run, new Trigger(what)));
			assertSame(expected, thrown, what);
			assertFalse(thrown instanceof InvocationTargetException || thrown instanceof UndeclaredThrowableException);
		}
	}

	@Test
	void aRequestOfTheWrongClassIsAClassCastException() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchangeHandler handler = orders.handler(new OrderService());

		assertThrows(ClassCastException.class, () -> handler.handle(exchange(orders, "placeOrder"), new OrderRef("1")));
	}

	@Test
	void anExchangeOfAnotherProtocolIsRefused() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchange foreign = exchange(context.getProtocol(Snake.class), "placeOrder");

		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> orders.handler(new OrderService()).handle(foreign, new NewOrder("A-1", 1)));
		assertNames(thrown.getMessage(), Snake.class.getName() + ".placeOrder", "not an exchange of this protocol",
				Orders.class.getName());
	}

	@Test
	void anExchangeOfTheSameInterfaceReadByAnotherContextIsRefused() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchange elsewhere = exchange(DataBindContext.builder().build().getProtocol(Orders.class), "placeOrder");

		assertThrows(IllegalArgumentException.class,
				() -> orders.handler(new OrderService()).handle(elsewhere, new NewOrder("A-1", 1)));
	}

	@Test
	void aNullTargetIsRefusedWhenTheHandlerIsMade() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);

		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> orders.handler(null));
		assertNames(thrown.getMessage(), "null", "not an instance of " + Orders.class.getName());
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void aTargetOfTheWrongClassIsRefusedWhenTheHandlerIsMadeWhereTheGenericSignatureWasBypassed()
			throws DataBindException {
		DataProtocol raw = context.getProtocol(Orders.class);

		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> raw.handler(new SealedService()));
		assertNames(thrown.getMessage(), SealedService.class.getName(), "not an instance of " + Orders.class.getName());
	}

	@Test
	void aScopedValueBoundAroundHandleIsVisibleInsideTheTargetsMethod() throws Throwable {
		List<String> seen = new ArrayList<>();
		Orders target = new OrderService() {
			@Override
			public Order getOrder(OrderRef request) {
				seen.add(CALLER.isBound() ? CALLER.get() : "<unbound>");
				return new Order(request.id(), 1);
			}
		};
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchangeHandler handler = orders.handler(target);
		DataExchange getOrder = exchange(orders, "getOrder");

		Object response = ScopedValue.where(CALLER, "alice").call(() -> handler.handle(getOrder, new OrderRef("1")));
		handler.handle(getOrder, new OrderRef("2"));

		assertEquals(new Order("1", 1), response);
		assertEquals(List.of("alice", "<unbound>"), seen);
	}

	@Test
	void aDecoratorRebindsTheScopedValueForTheHandlerItWraps() throws Throwable {
		List<String> seen = new ArrayList<>();
		Orders target = new OrderService() {
			@Override
			public Order getOrder(OrderRef request) {
				seen.add(CALLER.get());
				return new Order(request.id(), 1);
			}
		};
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchangeHandler service = orders.handler(target);
		DataExchangeHandler decorator = (exchange, request) ->
				ScopedValue.where(CALLER, CALLER.get() + " via " + exchange.name())
						.call(() -> service.handle(exchange, request));

		ScopedValue.where(CALLER, "alice").call(() -> decorator.handle(exchange(orders, "getOrder"),
				new OrderRef("1")));

		assertEquals(List.of("alice via getOrder"), seen);
	}

	@Test
	void aScopedValueIsUnboundInACallbackOnAnotherThread() throws Exception {
		Boolean boundInCallback = ScopedValue.where(CALLER, "alice")
				.call(() -> CompletableFuture.supplyAsync(CALLER::isBound).get());
		Boolean boundInScope = ScopedValue.where(CALLER, "alice").call(CALLER::isBound);

		assertFalse(boundInCallback);
		assertTrue(boundInScope);
	}

	@Test
	void aRouterIsAHandlerOverHandlers() throws Throwable {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchangeHandler service = orders.handler(new OrderService());
		DataExchangeHandler readOnly = (exchange, request) -> {
			throw new IllegalStateException(exchange.name() + " is not served here");
		};
		DataExchangeHandler router = (exchange, request) ->
				exchange.name().startsWith("get") || exchange.name().startsWith("list")
						? service.handle(exchange, request)
						: readOnly.handle(exchange, request);

		assertEquals(new Order("7", 1), router.handle(exchange(orders, "getOrder"), new OrderRef("7")));
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> router.handle(exchange(orders, "cancelOrder"), new OrderRef("7")));
		assertEquals("cancelOrder is not served here", refused.getMessage());
	}

	@Test
	void theHandlerReturnsNullWhereTheTargetReturnsNullForADeclaredResponse() throws Throwable {
		DataProtocol<Throwing> throwing = context.getProtocol(Throwing.class);

		assertNull(throwing.handler(new Thrower()).handle(exchange(throwing, "run"), new Trigger("null")));
	}

	@Test
	void aHandlerIsUsableFromManyThreadsAtOnce() throws Exception {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchangeHandler handler = orders.handler(new OrderService());
		DataExchange getOrder = exchange(orders, "getOrder");

		List<CompletableFuture<Object>> calls = new ArrayList<>();
		for (int i = 0; i < 64; i++) {
			String id = Integer.toString(i);
			calls.add(CompletableFuture.supplyAsync(() -> {
				try {
					return handler.handle(getOrder, new OrderRef(id));
				} catch (Throwable e) {
					throw new AssertionError(e);
				}
			}));
		}
		for (int i = 0; i < calls.size(); i++) {
			assertEquals(new Order(Integer.toString(i), 1), assertInstanceOf(Order.class, calls.get(i).get()));
		}
	}

	private static DataExchange exchange(DataProtocol<?> protocol, String name) {
		return protocol.exchange(name).orElseThrow();
	}
}
