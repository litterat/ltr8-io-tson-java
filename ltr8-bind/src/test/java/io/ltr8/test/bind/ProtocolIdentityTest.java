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
import io.ltr8.bind.DataProtocol;
import io.ltr8.test.data.Protocols.Catalogue;
import io.ltr8.test.data.Protocols.Orders;
import io.ltr8.test.data.RefusedProtocols.Overloaded;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A protocol is analysed once per context and shared: every call for one interface returns the same instance. */
class ProtocolIdentityTest {

	@Test
	void repeatedCallsReturnTheSameProtocol() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();

		assertSame(context.getProtocol(Orders.class), context.getProtocol(Orders.class));
	}

	@Test
	void repeatedCallsReturnTheSameExchangeInstances() throws DataBindException {
		DataBindContext context = DataBindContext.builder().build();

		assertSame(context.getProtocol(Orders.class).exchange("placeOrder").orElseThrow(),
				context.getProtocol(Orders.class).exchange("place_order").orElseThrow());
	}

	@Test
	void eachContextAnalysesItsOwn() throws DataBindException {
		assertNotSame(DataBindContext.builder().build().getProtocol(Orders.class),
				DataBindContext.builder().build().getProtocol(Orders.class));
	}

	@Test
	void aRefusedInterfaceIsRefusedEveryTime() {
		DataBindContext context = DataBindContext.builder().build();

		assertThrows(DataBindException.class, () -> context.getProtocol(Overloaded.class));
		assertThrows(DataBindException.class, () -> context.getProtocol(Overloaded.class));
	}

	@Test
	void concurrentCallsOnAFreshContextAgreeOnOneInstance() throws Exception {
		for (int round = 0; round < 20; round++) {
			DataBindContext context = DataBindContext.builder().build();
			int threads = 16;
			CountDownLatch start = new CountDownLatch(1);
			List<Future<DataProtocol<Catalogue>>> results = new ArrayList<>();
			try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
				for (int i = 0; i < threads; i++) {
					Callable<DataProtocol<Catalogue>> call = () -> {
						start.await();
						return context.getProtocol(Catalogue.class);
					};
					results.add(pool.submit(call));
				}
				start.countDown();
				DataProtocol<Catalogue> first = results.getFirst().get();
				for (Future<DataProtocol<Catalogue>> result : results) {
					assertSame(first, result.get());
				}
				assertSame(first, context.getProtocol(Catalogue.class));
			}
		}
	}
}
