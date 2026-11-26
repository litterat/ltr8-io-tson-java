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
package io.ltr8.test.data;

import io.ltr8.annotation.ToData;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Interfaces a protocol is read from, with their records, declared errors and implementations. The interfaces
 * refused as protocols are in {@link RefusedProtocols}.
 */
public final class Protocols {

	private Protocols() {
	}

	public record NewOrder(String sku, int quantity) {
	}

	public record Order(String sku, int quantity) {
	}

	public record OrderRef(String id) {
	}

	public record OrderQuery(String status) {
	}

	public record OrderPage(List<Order> items) {
	}

	public record OrderNotFound(String id) {
	}

	public record PaymentDeclined(String reason) {
	}

	public static class OrderNotFoundException extends Exception implements ToData<OrderNotFound> {

		private final OrderNotFound data;

		public OrderNotFoundException(OrderNotFound data) {
			super("no order " + data.id());
			this.data = data;
		}

		@Override
		public OrderNotFound toData() {
			return data;
		}
	}

	/** A subclass of a declared error, thrown and never declared beside it. */
	public static class ArchivedOrderException extends OrderNotFoundException implements ToData<OrderNotFound> {

		public ArchivedOrderException(OrderNotFound data) {
			super(data);
		}
	}

	/** An unchecked declared error: a {@code throws} clause names it, so it is declared. */
	public static class PaymentDeclinedException extends RuntimeException implements ToData<PaymentDeclined> {

		private final PaymentDeclined data;

		public PaymentDeclinedException(PaymentDeclined data) {
			super(data.reason());
			this.data = data;
		}

		@Override
		public PaymentDeclined toData() {
			return data;
		}
	}

	/** The proposal's example. */
	public interface Orders {

		Order placeOrder(NewOrder request) throws SkuNotFoundException;

		Order getOrder(OrderRef request) throws OrderNotFoundException;

		OrderPage listOrders(OrderQuery request);

		void cancelOrder(OrderRef request) throws OrderNotFoundException;
	}

	public static class OrderService implements Orders {

		@Override
		public Order placeOrder(NewOrder request) throws SkuNotFoundException {
			if (request.sku().startsWith("GONE")) {
				throw new SkuNotFoundException(new SkuNotFound(request.sku()));
			}
			return new Order(request.sku(), request.quantity());
		}

		@Override
		public Order getOrder(OrderRef request) throws OrderNotFoundException {
			if (request.id().startsWith("ARCHIVED")) {
				throw new ArchivedOrderException(new OrderNotFound(request.id()));
			}
			if (request.id().startsWith("GONE")) {
				throw new OrderNotFoundException(new OrderNotFound(request.id()));
			}
			return new Order(request.id(), 1);
		}

		@Override
		public OrderPage listOrders(OrderQuery request) {
			return new OrderPage(List.of(new Order(request.status(), 1), new Order(request.status(), 2)));
		}

		@Override
		public void cancelOrder(OrderRef request) throws OrderNotFoundException {
			if (request.id().startsWith("GONE")) {
				throw new OrderNotFoundException(new OrderNotFound(request.id()));
			}
		}
	}

	/** Every way a method of an interface is or is not an exchange, and every kind of response. */
	public interface Catalogue {

		int count();

		void ping();

		List<Order> recent(OrderRef request);

		Map<String, Integer> stock(OrderQuery request);

		String label(OrderRef request);

		Order charge(NewOrder request) throws PaymentDeclinedException, OrderNotFoundException, SkuNotFoundException;

		default String describe() {
			return "catalogue";
		}

		static Catalogue empty() {
			return null;
		}

		@Override
		String toString();

		@Override
		boolean equals(Object other);

		@Override
		int hashCode();
	}

	public interface Base {

		Object get(OrderRef request);

		Order lookup(OrderRef request);
	}

	/** Redeclares {@code get} with a narrower return, and inherits {@code lookup}. */
	public interface Narrowed extends Base {

		@Override
		Order get(OrderRef request);

		Order extra(OrderRef request);
	}

	public interface Generic<T> {

		Order fixed(OrderRef request);
	}

	/** A generic super-interface whose own methods name no type variable. */
	public interface FromGeneric extends Generic<Order> {
	}

	public sealed interface Sealed permits SealedService {

		Order getOrder(OrderRef request);
	}

	public record SealedService() implements Sealed {

		@Override
		public Order getOrder(OrderRef request) {
			return new Order("sealed " + request.id(), 1);
		}
	}

	public interface Empty {
	}

	interface PackagePrivateBase {

		Order inherited(OrderRef request);
	}

	/** Public, inheriting its one exchange from an interface the public lookup cannot reach. */
	public interface OverPackagePrivate extends PackagePrivateBase {
	}

	public static class OverPackagePrivateService implements OverPackagePrivate {

		@Override
		public Order inherited(OrderRef request) {
			return new Order("inherited " + request.id(), 1);
		}
	}

	/** Names spelt with {@code _}: one with and one without a Java spelling that differs. */
	public interface Snake {

		Order get_order(OrderRef request);

		Order placeOrder(NewOrder request);
	}

	public static class SnakeService implements Snake {

		@Override
		public Order get_order(OrderRef request) {
			return new Order("snake " + request.id(), 1);
		}

		@Override
		public Order placeOrder(NewOrder request) {
			return new Order("camel " + request.sku(), request.quantity());
		}
	}

	/** What a {@code Trigger}'s {@code what} asks {@link Thrower} to throw. */
	public record Trigger(String what) {
	}

	/** One exchange that throws whatever it is asked to, for what the invoker and a handler let through. */
	public interface Throwing {

		Order run(Trigger request) throws SkuNotFoundException;

		Order nothing() throws OrderNotFoundException;

		void quiet(Trigger request);
	}

	public static class Thrower implements Throwing {

		public static final SkuNotFoundException DECLARED = new SkuNotFoundException(new SkuNotFound("declared"));
		public static final IllegalStateException RUNTIME = new IllegalStateException("a bug");
		public static final AssertionError ERROR = new AssertionError("an error");
		public static final IOException CHECKED = new IOException("checked, undeclared");

		@Override
		public Order run(Trigger request) throws SkuNotFoundException {
			switch (request.what()) {
				case "declared" -> throw DECLARED;
				case "runtime" -> throw RUNTIME;
				case "error" -> throw ERROR;
				case "checked" -> throw Protocols.<RuntimeException>sneaky(CHECKED);
				case "null" -> {
					return null;
				}
				default -> {
					return new Order(request.what(), 1);
				}
			}
		}

		@Override
		public Order nothing() {
			return new Order("nothing", 0);
		}

		@Override
		public void quiet(Trigger request) {
			if (request != null && request.what().equals("runtime")) {
				throw RUNTIME;
			}
		}
	}

	/** {@code thrown}, thrown as though it were an {@code X}: how a checked exception escapes undeclared. */
	@SuppressWarnings("unchecked")
	public static <X extends Throwable> X sneaky(Throwable thrown) throws X {
		throw (X) thrown;
	}
}
