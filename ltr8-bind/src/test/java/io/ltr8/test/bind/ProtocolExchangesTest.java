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
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataExchange;
import io.ltr8.bind.DataProtocol;
import io.ltr8.test.data.Protocols.Base;
import io.ltr8.test.data.Protocols.Catalogue;
import io.ltr8.test.data.Protocols.Closes;
import io.ltr8.test.data.Protocols.Empty;
import io.ltr8.test.data.Protocols.FromGeneric;
import io.ltr8.test.data.Protocols.NewOrder;
import io.ltr8.test.data.Protocols.Narrowed;
import io.ltr8.test.data.Protocols.Order;
import io.ltr8.test.data.Protocols.OrderNotFound;
import io.ltr8.test.data.Protocols.OrderNotFoundException;
import io.ltr8.test.data.Protocols.OrderPage;
import io.ltr8.test.data.Protocols.OrderRef;
import io.ltr8.test.data.Protocols.OrderStore;
import io.ltr8.test.data.Protocols.Orders;
import io.ltr8.test.data.Protocols.OverPackagePrivate;
import io.ltr8.test.data.Protocols.OverPackagePrivateService;
import io.ltr8.test.data.Protocols.PaymentDeclined;
import io.ltr8.test.data.Protocols.PaymentDeclinedException;
import io.ltr8.test.data.Protocols.Sealed;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which methods of an interface are its exchanges, and what each exchange's request, response and errors are: the
 * abstract instance methods, inherited ones included, each a {@link DataClass} the context already gives.
 */
class ProtocolExchangesTest {

	private final DataBindContext context = DataBindContext.builder().build();

	@Test
	void eachAbstractMethodOfTheProposalsInterfaceIsAnExchange() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);

		assertSame(Orders.class, orders.typeClass());
		assertEquals(List.of("cancelOrder", "getOrder", "listOrders", "placeOrder"), names(orders));
	}

	@Test
	void anExchangeTakingOneRecordHasThatRecordAsItsRequest() throws DataBindException {
		DataExchange placeOrder = exchange(Orders.class, "placeOrder");

		DataClass request = placeOrder.request().orElseThrow();
		assertInstanceOf(DataClassRecord.class, request);
		assertSame(context.getDescriptor(NewOrder.class), request);
	}

	@Test
	void anExchangeTakingNothingHasNoRequest() throws DataBindException {
		assertTrue(exchange(Catalogue.class, "count").request().isEmpty());
		assertTrue(exchange(Catalogue.class, "ping").request().isEmpty());
	}

	@Test
	void aVoidExchangeHasNoResponse() throws DataBindException {
		assertTrue(exchange(Orders.class, "cancelOrder").response().isEmpty());
		assertTrue(exchange(Catalogue.class, "ping").response().isEmpty());
	}

	@Test
	void aRecordResponseIsTheContextsOwnDescriptor() throws DataBindException {
		assertSame(context.getDescriptor(Order.class), exchange(Orders.class, "placeOrder").response().orElseThrow());
		assertSame(context.getDescriptor(OrderPage.class),
				exchange(Orders.class, "listOrders").response().orElseThrow());
	}

	@Test
	void aResponseIsAnyValueTheContextDescribesNotOnlyARecord() throws DataBindException {
		DataClass list = exchange(Catalogue.class, "recent").response().orElseThrow();
		assertInstanceOf(DataClassArray.class, list);

		DataClass map = exchange(Catalogue.class, "stock").response().orElseThrow();
		assertInstanceOf(DataClassMap.class, map);

		DataClass count = exchange(Catalogue.class, "count").response().orElseThrow();
		assertSame(int.class, assertInstanceOf(DataClassAtom.class, count).typeClass());

		DataClass label = exchange(Catalogue.class, "label").response().orElseThrow();
		assertSame(String.class, assertInstanceOf(DataClassAtom.class, label).typeClass());
	}

	@Test
	void anExchangeWithoutAThrowsClauseHasNoErrors() throws DataBindException {
		assertEquals(List.of(), exchange(Orders.class, "listOrders").errors());
	}

	@Test
	void eachDeclaredErrorIsTheThrowableBridgedToItsRecord() throws DataBindException {
		DataExchange placeOrder = exchange(Orders.class, "placeOrder");

		DataClass error = placeOrder.errors().getFirst();
		assertEquals(1, placeOrder.errors().size());
		assertSame(SkuNotFoundException.class, error.typeClass());
		assertSame(SkuNotFound.class, error.dataClass());
		assertTrue(error.bridge().isPresent());
		assertSame(context.getDescriptor(SkuNotFoundException.class), error);
	}

	@Test
	void theErrorsAreInTheOrderTheThrowsClauseNamesThem() throws DataBindException {
		List<DataClass> errors = exchange(Catalogue.class, "charge").errors();

		assertEquals(List.of(PaymentDeclinedException.class, OrderNotFoundException.class,
				SkuNotFoundException.class), errors.stream().map(DataClass::typeClass).toList());
		assertEquals(List.of(PaymentDeclined.class, OrderNotFound.class, SkuNotFound.class),
				errors.stream().map(DataClass::dataClass).toList());
	}

	@Test
	void anUncheckedExceptionTheThrowsClauseNamesIsADeclaredError() throws DataBindException {
		assertTrue(exchange(Catalogue.class, "charge").errors().stream()
				.anyMatch(error -> error.typeClass() == PaymentDeclinedException.class));
	}

	@Test
	void defaultAndStaticMethodsAreNotExchanges() throws DataBindException {
		DataProtocol<Catalogue> catalogue = context.getProtocol(Catalogue.class);

		assertTrue(catalogue.exchange("describe").isEmpty());
		assertTrue(catalogue.exchange("empty").isEmpty());
	}

	@Test
	void theSignaturesOfEqualsHashCodeAndToStringAreNotExchangesEvenWhereRestated() throws Exception {
		// The interface restates all three, and reflection lists each as its own abstract method.
		for (String name : List.of("equals", "hashCode", "toString")) {
			Method restated = Arrays.stream(Catalogue.class.getMethods())
					.filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
			assertSame(Catalogue.class, restated.getDeclaringClass());
			assertTrue(Modifier.isAbstract(restated.getModifiers()));
		}

		assertEquals(List.of("charge", "count", "label", "ping", "recent", "stock"),
				names(context.getProtocol(Catalogue.class)));
	}

	@Test
	void anInheritedAbstractMethodIsAnExchange() throws DataBindException {
		assertEquals(List.of("extra", "get", "lookup"), names(context.getProtocol(Narrowed.class)));
	}

	@Test
	void aMethodRedeclaredWithANarrowerReturnIsOneExchangeTheRedeclaration() throws Exception {
		// Reflection lists two: the abstract redeclaration, and the bridge the compiler leaves for the wider
		// return, which is not abstract.
		List<Method> gets = Arrays.stream(Narrowed.class.getMethods())
				.filter(method -> method.getName().equals("get")).toList();
		assertEquals(2, gets.size());
		Method bridge = gets.stream().filter(Method::isBridge).findFirst().orElseThrow();
		assertSame(Object.class, bridge.getReturnType());
		assertFalse(Modifier.isAbstract(bridge.getModifiers()));

		DataExchange get = exchange(Narrowed.class, "get");
		assertSame(context.getDescriptor(Order.class), get.response().orElseThrow());
	}

	@Test
	void theWiderReturnARedeclarationNarrowsIsStillRefusedOnTheSuperInterface() {
		// Object has no descriptor, so Base.get is refused where Narrowed.get is not.
		DataBindException thrown = assertThrows(DataBindException.class, () -> context.getProtocol(Base.class));
		assertTrue(thrown.getMessage().contains(Base.class.getName() + ".get's response java.lang.Object"),
				thrown.getMessage());
	}

	@Test
	void aGenericMethodRedeclaredWithItsTypesFixedIsOneExchangeTheRedeclaration() throws Throwable {
		DataProtocol<OrderStore> protocol = context.getProtocol(OrderStore.class);
		assertEquals(List.of("fetch"), names(protocol));

		DataExchange fetch = protocol.exchanges().get(0);
		assertSame(context.getDescriptor(OrderRef.class), fetch.request().orElseThrow());
		assertSame(context.getDescriptor(Order.class), fetch.response().orElseThrow());

		OrderStore store = request -> new Order("stored " + request.id(), 1);
		assertEquals(new Order("stored 4", 1), protocol.handler(store).handle(fetch, new OrderRef("4")));
	}

	@Test
	void aRedeclarationWithoutTheInheritedThrowsClauseDeclaresNoErrors() throws DataBindException {
		assertEquals(List.of("close", "getOrder"), names(context.getProtocol(Closes.class)));
		assertEquals(List.of(), exchange(Closes.class, "close").errors());
	}

	@Test
	void aMethodInheritedFromAnInterfaceThePublicLookupCannotReachIsCalledThroughThePublicOne() throws Throwable {
		DataProtocol<OverPackagePrivate> protocol = context.getProtocol(OverPackagePrivate.class);
		DataExchange inherited = protocol.exchange("inherited").orElseThrow();

		assertEquals(new Order("inherited 1", 1),
				protocol.handler(new OverPackagePrivateService()).handle(inherited, new OrderRef("1")));
	}

	@Test
	void aGenericSuperInterfaceWhoseMethodsNameNoTypeVariableIsAProtocol() throws DataBindException {
		assertEquals(List.of("fixed"), names(context.getProtocol(FromGeneric.class)));
	}

	@Test
	void aSealedInterfaceIsAProtocol() throws DataBindException {
		assertEquals(List.of("getOrder"), names(context.getProtocol(Sealed.class)));
	}

	@Test
	void anInterfaceWithNoAbstractMethodIsAProtocolWithNoExchanges() throws DataBindException {
		assertEquals(List.of(), context.getProtocol(Empty.class).exchanges());
	}

	@Test
	void theDescriptorsListsCannotBeChanged() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchange placeOrder = orders.exchange("placeOrder").orElseThrow();

		assertThrows(UnsupportedOperationException.class, () -> orders.exchanges().clear());
		assertThrows(UnsupportedOperationException.class, () -> placeOrder.errors().clear());
	}

	private DataExchange exchange(Class<?> type, String name) throws DataBindException {
		return context.getProtocol(type).exchange(name).orElseThrow();
	}

	private static List<String> names(DataProtocol<?> protocol) {
		return protocol.exchanges().stream().map(DataExchange::name).toList();
	}
}
