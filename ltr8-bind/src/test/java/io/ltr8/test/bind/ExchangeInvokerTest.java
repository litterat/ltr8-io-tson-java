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
import io.ltr8.test.data.Protocols.Catalogue;
import io.ltr8.test.data.Protocols.Narrowed;
import io.ltr8.test.data.Protocols.NewOrder;
import io.ltr8.test.data.Protocols.Order;
import io.ltr8.test.data.Protocols.OrderPage;
import io.ltr8.test.data.Protocols.OrderQuery;
import io.ltr8.test.data.Protocols.OrderRef;
import io.ltr8.test.data.Protocols.OrderService;
import io.ltr8.test.data.Protocols.Orders;
import io.ltr8.test.data.Protocols.Thrower;
import io.ltr8.test.data.Protocols.Throwing;
import io.ltr8.test.data.Protocols.Trigger;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An exchange's invoker is a bare handle, {@code (Object target, Object request) -> Object}, that behaves as the
 * method does: it returns the response, {@code null} for {@code void}, and lets every throwable through untouched.
 */
class ExchangeInvokerTest {

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void theInvokerTakesTwoObjectsAndReturnsAnObject() throws DataBindException {
		for (DataExchange exchange : context.getProtocol(Catalogue.class).exchanges()) {
			assertEquals(MethodType.methodType(Object.class, Object.class, Object.class), exchange.invoker().type(),
					exchange.name());
		}
	}

	@Test
	void theInvokerReturnsTheResponse() throws Throwable {
		MethodHandle placeOrder = invoker(Orders.class, "placeOrder");

		Object response = placeOrder.invokeExact((Object) new OrderService(), (Object) new NewOrder("A-1", 2));
		assertEquals(new Order("A-1", 2), response);

		Object page = invoker(Orders.class, "listOrders")
				.invokeExact((Object) new OrderService(), (Object) new OrderQuery("open"));
		assertEquals(new OrderPage(List.of(new Order("open", 1), new Order("open", 2))), page);
	}

	@Test
	void aVoidExchangeReturnsNull() throws Throwable {
		Object response = invoker(Orders.class, "cancelOrder")
				.invokeExact((Object) new OrderService(), (Object) new OrderRef("1"));
		assertNull(response);
	}

	@Test
	void aPrimitiveResponseComesBackBoxed() throws Throwable {
		Catalogue catalogue = new CatalogueOf(3);

		Object count = invoker(Catalogue.class, "count").invokeExact((Object) catalogue, (Object) null);
		assertEquals(Integer.valueOf(3), count);
	}

	@Test
	void anExchangeTakingNothingIsHandedNullAndDropsWhateverElseItIsHanded() throws Throwable {
		MethodHandle nothing = invoker(Throwing.class, "nothing");
		Order expected = new Order("nothing", 0);

		assertEquals(expected, nothing.invokeExact((Object) new Thrower(), (Object) null));
		assertEquals(expected, nothing.invokeExact((Object) new Thrower(), (Object) "not examined"));
		assertEquals(expected, nothing.invokeExact((Object) new Thrower(), (Object) new OrderRef("1")));
	}

	@Test
	void aNullRequestIsPassedToTheMethodUnjudged() throws Throwable {
		// quiet throws only when handed a Trigger asking it to, so a null reaching it returns normally.
		assertNull(invoker(Throwing.class, "quiet").invokeExact((Object) new Thrower(), (Object) null));
	}

	@Test
	void aNullResponseFromAnExchangeDeclaringOnePassesThrough() throws Throwable {
		assertNull(invoker(Throwing.class, "run").invokeExact((Object) new Thrower(), (Object) new Trigger("null")));
	}

	@Test
	void aDeclaredErrorIsThrownAsTheSameInstance() throws Throwable {
		assertSame(Thrower.DECLARED, thrownBy("declared"));
	}

	@Test
	void anUndeclaredRuntimeExceptionIsThrownAsTheSameInstance() throws Throwable {
		assertSame(Thrower.RUNTIME, thrownBy("runtime"));
	}

	@Test
	void anErrorIsThrownAsTheSameInstance() throws Throwable {
		assertSame(Thrower.ERROR, thrownBy("error"));
	}

	@Test
	void aCheckedExceptionThrownSneakilyIsThrownAsTheSameInstance() throws Throwable {
		assertSame(Thrower.CHECKED, thrownBy("checked"));
	}

	@Test
	void aRequestOfTheWrongClassIsAClassCastException() throws DataBindException {
		MethodHandle placeOrder = invoker(Orders.class, "placeOrder");

		assertThrows(ClassCastException.class,
				() -> placeOrder.invokeExact((Object) new OrderService(), (Object) new OrderRef("1")));
	}

	@Test
	void aTargetOfTheWrongClassIsAClassCastException() throws DataBindException {
		MethodHandle placeOrder = invoker(Orders.class, "placeOrder");

		assertThrows(ClassCastException.class,
				() -> placeOrder.invokeExact((Object) "not an Orders", (Object) new NewOrder("A-1", 1)));
	}

	@Test
	void aNullTargetIsANullPointerException() throws DataBindException {
		MethodHandle placeOrder = invoker(Orders.class, "placeOrder");

		assertThrows(NullPointerException.class,
				() -> placeOrder.invokeExact((Object) null, (Object) new NewOrder("A-1", 1)));
	}

	@Test
	void theInvokerOfAnInheritedMethodCallsTheTargetsImplementation() throws Throwable {
		Object response = invoker(Narrowed.class, "lookup")
				.invokeExact((Object) new NarrowedService(), (Object) new OrderRef("9"));
		assertEquals(new Order("lookup 9", 1), response);
	}

	/** What the invoker of {@code run} throws for {@code what}, caught as the {@code Throwable} it is. */
	private Throwable thrownBy(String what) throws DataBindException {
		MethodHandle run = invoker(Throwing.class, "run");
		return assertThrows(Throwable.class,
				() -> {
					Object ignored = run.invokeExact((Object) new Thrower(), (Object) new Trigger(what));
				});
	}

	private MethodHandle invoker(Class<?> type, String name) throws DataBindException {
		return context.getProtocol(type).exchange(name).orElseThrow().invoker();
	}

	static final class NarrowedService implements Narrowed {

		@Override
		public Order get(OrderRef request) {
			return new Order("get " + request.id(), 1);
		}

		@Override
		public Order lookup(OrderRef request) {
			return new Order("lookup " + request.id(), 1);
		}

		@Override
		public Order extra(OrderRef request) {
			return new Order("extra " + request.id(), 1);
		}
	}

	record CatalogueOf(int count) implements Catalogue {

		@Override
		public void ping() {
		}

		@Override
		public List<Order> recent(OrderRef request) {
			return List.of();
		}

		@Override
		public Map<String, Integer> stock(OrderQuery request) {
			return Map.of();
		}

		@Override
		public String label(OrderRef request) {
			return request.id();
		}

		@Override
		public Order charge(NewOrder request) {
			return new Order(request.sku(), request.quantity());
		}
	}
}
