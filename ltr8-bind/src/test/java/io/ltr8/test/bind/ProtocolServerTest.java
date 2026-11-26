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
import io.ltr8.bind.DataExchange;
import io.ltr8.bind.DataExchangeHandler;
import io.ltr8.bind.DataProtocol;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.Protocols.Order;
import io.ltr8.test.data.Protocols.OrderRef;
import io.ltr8.test.data.Protocols.Orders;
import io.ltr8.test.data.Protocols.OrderService;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The proposal's "A server over it", end to end, with maps standing in for a transport: an exchange found by the
 * name a call carries, its request read from data through the mappers, the handler called through a decorator,
 * and the response or the declared error's record written back as data.
 */
class ProtocolServerTest {

	/** What the edge writes back: a status and a body, as an HTTP edge would. */
	record Reply(int status, Object body) {
	}

	private final DataBindContext context = DataBindContext.builder().build();
	private final MapMapper mapper = new MapMapper(context);
	private final List<String> log = new ArrayList<>();

	private final DataProtocol<Orders> orders;
	private final DataExchangeHandler handler;
	private final Map<DataClass, Integer> statusOf = new IdentityHashMap<>();

	ProtocolServerTest() throws DataBindException {
		// Startup, on one thread: a refusal here would be a DataBindException naming the interface and method.
		orders = context.getProtocol(Orders.class);
		DataExchangeHandler service = orders.handler(new OrderService());
		handler = (exchange, request) -> {
			try {
				return service.handle(exchange, request);
			} finally {
				log.add(exchange.name());
			}
		};
		// What the edge knows per declared error, keyed by the DataClass errors() lists, worked out once.
		for (DataExchange exchange : orders.exchanges()) {
			for (DataClass error : exchange.errors()) {
				statusOf.put(error, 404);
			}
		}
	}

	/** One call: the exchange's name as the data spells it, and its body. */
	private Reply call(String name, Map<String, Object> body) throws Throwable {
		Optional<DataExchange> found = orders.exchange(name);
		if (found.isEmpty()) {
			return new Reply(404, Map.of("unknown exchange", name));
		}
		DataExchange exchange = found.get();
		Object request = exchange.request().isPresent()
				? mapper.toObject(exchange.request().get(), body)
				: null;
		try {
			Object response = handler.handle(exchange, request);
			return exchange.response().isPresent()
					? new Reply(200, mapper.toMap(exchange.response().get(), response))
					: new Reply(204, null);
		} catch (Throwable thrown) {
			Optional<DataClass> error = exchange.error(thrown);
			if (error.isEmpty()) {
				throw thrown;
			}
			Object record = error.get().bridge().orElseThrow().toData(thrown);
			return new Reply(statusOf.get(error.get()), mapper.toMap(record));
		}
	}

	@Test
	void aRequestReadFromDataIsAnsweredWithItsResponseAsData() throws Throwable {
		Reply reply = call("place_order", Map.of("sku", "A-1", "quantity", 2));

		assertEquals(new Reply(200, Map.of("sku", "A-1", "quantity", 2)), reply);
		assertEquals(List.of("placeOrder"), log);
	}

	@Test
	void aResponseHoldingAListIsWrittenAsData() throws Throwable {
		Reply reply = call("list_orders", Map.of("status", "open"));

		// MapMapper writes a list as an array.
		Map<?, ?> body = assertInstanceOf(Map.class, reply.body());
		assertEquals(200, reply.status());
		assertEquals(List.of(Map.of("sku", "open", "quantity", 1), Map.of("sku", "open", "quantity", 2)),
				List.of(assertInstanceOf(Object[].class, body.get("items"))));
	}

	@Test
	void aVoidExchangeAnswersWithNoBody() throws Throwable {
		assertEquals(new Reply(204, null), call("cancel_order", Map.of("id", "1")));
	}

	@Test
	void aDeclaredErrorIsWrittenAsItsRecordWithTheStatusKeyedOnItsDataClass() throws Throwable {
		Reply reply = call("place_order", Map.of("sku", "GONE-1", "quantity", 1));

		assertEquals(new Reply(404, Map.of("sku", "GONE-1")), reply);
		assertEquals(List.of("placeOrder"), log, "the decorator saw the call that threw");
	}

	@Test
	void aSubclassOfADeclaredErrorIsWrittenAsTheDeclaredErrorsRecord() throws Throwable {
		assertEquals(new Reply(404, Map.of("id", "ARCHIVED-1")), call("getOrder", Map.of("id", "ARCHIVED-1")));
	}

	@Test
	void anUndeclaredThrowableTakesTheEdgesFaultPathAsItself() throws DataBindException {
		Orders failing = new OrderService() {
			@Override
			public Order getOrder(OrderRef request) {
				throw new IllegalStateException("a bug");
			}
		};
		DataExchangeHandler service = orders.handler(failing);
		DataExchange getOrder = orders.exchange("get_order").orElseThrow();

		IllegalStateException bug = assertThrows(IllegalStateException.class,
				() -> service.handle(getOrder, mapper.toObject(getOrder.request().orElseThrow(), Map.of("id", "1"))));
		assertEquals("a bug", bug.getMessage());
		assertEquals(Optional.empty(), getOrder.error(bug));
	}

	@Test
	void aRemoteHandlerRebuildsTheDeclaredErrorFromTheRecordItReadsBack() throws Throwable {
		// The same picture reversed: a handler forwarding elsewhere throws the error built from the record.
		DataExchange placeOrder = orders.exchange("placeOrder").orElseThrow();
		DataClass declared = placeOrder.errors().getFirst();
		Map<String, Object> readBack = Map.of("sku", "GONE-2");
		DataExchangeHandler remote = (exchange, request) -> {
			Object record = mapper.toObject(declared.dataClass(), readBack);
			throw (Throwable) declared.bridge().orElseThrow().toObject(record);
		};

		Throwable thrown = assertThrows(Throwable.class, () -> remote.handle(placeOrder, null));
		SkuNotFoundException rebuilt = assertInstanceOf(SkuNotFoundException.class, thrown);
		assertEquals("GONE-2", rebuilt.sku());
		assertSame(declared, placeOrder.error(thrown).orElseThrow());
		assertEquals(new SkuNotFound("GONE-2"), rebuilt.toData());
	}

	@Test
	void anUnknownExchangeNameIsTheEdgesToAnswer() throws Throwable {
		assertEquals(new Reply(404, Map.of("unknown exchange", "PlaceOrder")), call("PlaceOrder", Map.of()));
	}
}
