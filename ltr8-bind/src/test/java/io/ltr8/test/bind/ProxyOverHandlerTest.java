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
import io.ltr8.test.data.Protocols.Base;
import io.ltr8.test.data.Protocols.Catalogue;
import io.ltr8.test.data.Protocols.Narrowed;
import io.ltr8.test.data.Protocols.NewOrder;
import io.ltr8.test.data.Protocols.Order;
import io.ltr8.test.data.Protocols.OrderRef;
import io.ltr8.test.data.Protocols.OrderService;
import io.ltr8.test.data.Protocols.Orders;
import io.ltr8.test.data.Protocols.Sealed;
import io.ltr8.test.data.Protocols.SealedService;
import io.ltr8.test.data.Protocols.Thrower;
import io.ltr8.test.data.Protocols.Throwing;
import io.ltr8.test.data.Protocols.Trigger;
import io.ltr8.test.data.ToDataBridges.SkuNotFound;
import io.ltr8.test.data.ToDataBridges.SkuNotFoundException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bind ships no proxy; a client that wants one builds it over the descriptor in a few lines, finding the exchange
 * by the called method's name and calling {@code handle}. These are the proposal's measured facts for whoever
 * does, with the two ways of building one: {@link Proxy}, and a hidden class spun with {@code java.lang.classfile}.
 */
class ProxyOverHandlerTest {

	private final DataBindContext context = DataBindContext.builder().build();

	/** Every method call a {@link #proxy} hands its invocation handler, as reflection presents it. */
	private final List<Method> calls = new ArrayList<>();

	@Test
	void aProxyOverAHandlerAnswersAsTheTargetDoes() throws Throwable {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		Orders proxy = proxy(orders, orders.handler(new OrderService()));

		assertEquals(new Order("A-1", 2), proxy.placeOrder(new NewOrder("A-1", 2)));
		assertEquals(new Order("7", 1), proxy.getOrder(new OrderRef("7")));
		proxy.cancelOrder(new OrderRef("7"));
		assertThrows(SkuNotFoundException.class, () -> proxy.placeOrder(new NewOrder("GONE-1", 1)));
	}

	@Test
	void aProxyThrowsADeclaredErrorARemoteHandlerBuiltFromItsRecord() throws DataBindException {
		DataProtocol<Orders> orders = context.getProtocol(Orders.class);
		DataExchangeHandler remote = (exchange, request) -> {
			DataClass error = exchange.errors().getFirst();
			throw (Throwable) error.bridge().orElseThrow().toObject(new SkuNotFound("remote"));
		};
		Orders proxy = proxy(orders, remote);

		SkuNotFoundException thrown = assertThrows(SkuNotFoundException.class,
				() -> proxy.placeOrder(new NewOrder("A-1", 1)));
		assertEquals("remote", thrown.sku());
	}

	@Test
	void aProxyRunsADefaultMethodThroughInvokeDefault() throws DataBindException {
		DataProtocol<Catalogue> catalogue = context.getProtocol(Catalogue.class);
		Catalogue proxy = proxy(catalogue, (exchange, request) -> {
			throw new AssertionError("describe is no exchange");
		});

		assertEquals("catalogue", proxy.describe());
	}

	@Test
	void objectsMethodsReachTheProxyAsObjectsEvenWhereTheInterfaceRestatesThem() throws DataBindException {
		DataProtocol<Catalogue> catalogue = context.getProtocol(Catalogue.class);
		Catalogue proxy = proxy(catalogue, (exchange, request) -> {
			throw new AssertionError(exchange.name() + " is answered by the proxy itself");
		});

		assertEquals("proxy of " + Catalogue.class.getName(), proxy.toString());
		assertEquals(System.identityHashCode(proxy), proxy.hashCode());
		assertTrue(proxy.equals(proxy));
		assertEquals(3, calls.size());
		for (Method call : calls) {
			assertSame(Object.class, call.getDeclaringClass(), call.getName());
		}
	}

	@Test
	void aProxyWrapsAnUndeclaredCheckedThrowableAndPassesTheRestThrough() throws DataBindException {
		DataProtocol<Throwing> throwing = context.getProtocol(Throwing.class);
		Throwing proxy = proxy(throwing, throwing.handler(new Thrower()));

		UndeclaredThrowableException wrapped = assertThrows(UndeclaredThrowableException.class,
				() -> proxy.run(new Trigger("checked")));
		assertSame(Thrower.CHECKED, wrapped.getCause());
		assertSame(Thrower.DECLARED, assertThrows(SkuNotFoundException.class, () -> proxy.run(new Trigger("declared"))));
		assertSame(Thrower.RUNTIME, assertThrows(IllegalStateException.class, () -> proxy.run(new Trigger("runtime"))));
	}

	@Test
	void aRedeclaredMethodCalledThroughTheSuperInterfaceReachesTheProxyAsTheRedeclaration() throws Exception {
		DataProtocol<Narrowed> narrowed = context.getProtocol(Narrowed.class);
		Narrowed proxy = proxy(narrowed, (exchange, request) -> new Order(exchange.name(), 1));

		Base asBase = proxy;
		assertEquals(new Order("get", 1), asBase.get(new OrderRef("1")));
		assertEquals(new Order("get", 1), proxy.get(new OrderRef("1")));
		for (Method call : calls) {
			assertSame(Narrowed.class, call.getDeclaringClass());
			assertSame(Order.class, call.getReturnType());
		}
	}

	@Test
	void aSealedInterfaceIsAHandlerButCannotBeProxied() throws Throwable {
		DataProtocol<Sealed> sealed = context.getProtocol(Sealed.class);
		DataExchangeHandler handler = sealed.handler(new SealedService());

		assertEquals(new Order("sealed 1", 1),
				handler.handle(sealed.exchange("getOrder").orElseThrow(), new OrderRef("1")));
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> proxy(sealed, handler));
		assertTrue(refused.getMessage().contains("is a sealed interface"), refused.getMessage());
	}

	@Test
	void methodHandlesAloneMakeOnlyASingleMethodInterface() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> MethodHandleProxies.asInterfaceInstance(Orders.class,
						MethodHandles.constant(Object.class, null)));
		assertTrue(refused.getMessage().contains("not a single-method interface"), refused.getMessage());
	}

	// --- a hidden class spun with java.lang.classfile, each abstract method one handle call

	/** What the spun class calls: the exchange's name and the request, so it holds nothing of bind. */
	public interface NamedHandler {

		Object handle(String exchange, Object request) throws Throwable;
	}

	/** Orders with a default method, which the spun class needs no code for. */
	public interface Desk extends Orders {

		default String describe() {
			return "desk";
		}
	}

	@Test
	void aSpunClassAnswersAsTheHandlerDoesAndRunsDefaultAndObjectMethodsWithNoCode() throws Throwable {
		DataProtocol<Desk> desk = context.getProtocol(Desk.class);
		DataExchangeHandler handler = desk.handler(new DeskService());
		Desk spun = spin(desk, (name, request) -> handler.handle(desk.exchange(name).orElseThrow(), request));

		assertTrue(spun.getClass().isHidden());
		assertEquals(new Order("A-1", 2), spun.placeOrder(new NewOrder("A-1", 2)));
		assertEquals("desk", spun.describe());
		assertTrue(spun.equals(spun));
		assertEquals(System.identityHashCode(spun), spun.hashCode());
	}

	@Test
	void aSpunClassPassesAnUndeclaredCheckedThrowableThroughAsItself() throws Throwable {
		DataProtocol<Desk> desk = context.getProtocol(Desk.class);
		Desk spun = spin(desk, (name, request) -> {
			throw Thrower.CHECKED;
		});

		assertSame(Thrower.CHECKED, assertThrows(IOException.class, () -> spun.cancelOrder(new OrderRef("1"))));
	}

	@Test
	void aSpunClassCannotImplementASealedInterface() throws DataBindException {
		// Sealed in the spun class's own package, so the refusal is the permits list's and not the package's.
		DataProtocol<Shut> shut = context.getProtocol(Shut.class);

		IncompatibleClassChangeError refused = assertThrows(IncompatibleClassChangeError.class,
				() -> spin(shut, (name, request) -> null));
		assertTrue(refused.getMessage().contains("is not a permitted subclass"), refused.getMessage());
	}

	public static final class DeskService extends OrderService implements Desk {
	}

	public sealed interface Shut permits ShutService {

		Order getOrder(OrderRef request);
	}

	public record ShutService() implements Shut {

		@Override
		public Order getOrder(OrderRef request) {
			return new Order(request.id(), 1);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T proxy(DataProtocol<T> protocol, DataExchangeHandler handler) {
		Class<T> type = protocol.typeClass();
		InvocationHandler invocation = (proxy, method, args) -> {
			calls.add(method);
			if (method.isDefault()) {
				return InvocationHandler.invokeDefault(proxy, method, args);
			}
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "equals" -> proxy == args[0];
					case "hashCode" -> System.identityHashCode(proxy);
					default -> "proxy of " + type.getName();
				};
			}
			DataExchange exchange = protocol.exchange(method.getName()).orElseThrow();
			return handler.handle(exchange, args == null ? null : args[0]);
		};
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, invocation);
	}

	/** A hidden class implementing {@code protocol}'s interface, each exchange taking one record or nothing. */
	private static <T> T spin(DataProtocol<T> protocol, NamedHandler handler) throws Throwable {
		Class<T> type = protocol.typeClass();
		ClassDesc self = ClassDesc.of(ProxyOverHandlerTest.class.getPackageName() + ".Spun");
		ClassDesc handlerDesc = NamedHandler.class.describeConstable().orElseThrow();
		byte[] bytes = ClassFile.of().build(self, spun -> {
			spun.withFlags(AccessFlag.FINAL, AccessFlag.SYNTHETIC)
					.withInterfaceSymbols(type.describeConstable().orElseThrow());
			spun.withField("handler", handlerDesc, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
			spun.withMethodBody(INIT_NAME, MethodTypeDesc.of(CD_void, handlerDesc), ClassFile.ACC_PUBLIC, code -> code
					.aload(0).invokespecial(CD_Object, INIT_NAME, MTD_void)
					.aload(0).aload(1).putfield(self, "handler", handlerDesc).return_());
			for (Method method : type.getMethods()) {
				if (!Modifier.isAbstract(method.getModifiers())) {
					continue;
				}
				MethodTypeDesc descriptor = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
						.describeConstable().orElseThrow();
				spun.withMethodBody(method.getName(), descriptor, ClassFile.ACC_PUBLIC, code -> {
					code.aload(0).getfield(self, "handler", handlerDesc).ldc(method.getName());
					if (method.getParameterCount() == 0) {
						code.aconst_null();
					} else {
						code.aload(1);
					}
					code.invokeinterface(handlerDesc, "handle", MethodTypeDesc.of(CD_Object, CD_String, CD_Object));
					if (method.getReturnType() == void.class) {
						code.pop().return_();
					} else {
						code.checkcast(method.getReturnType().describeConstable().orElseThrow()).areturn();
					}
				});
			}
		});
		Class<?> spun = MethodHandles.lookup().defineHiddenClass(bytes, true).lookupClass();
		return type.cast(spun.getConstructor(NamedHandler.class).newInstance(handler));
	}

	@Test
	void theSpunClassAndTheProxyAgreeWithTheHandlerOnAVoidExchange() throws Throwable {
		DataProtocol<Desk> desk = context.getProtocol(Desk.class);
		List<String> seen = new ArrayList<>();
		NamedHandler named = (name, request) -> {
			seen.add(name + " " + assertInstanceOf(OrderRef.class, request).id());
			return null;
		};
		Desk spun = spin(desk, named);
		Desk proxy = proxy(desk, (exchange, request) -> named.handle(exchange.name(), request));

		spun.cancelOrder(new OrderRef("1"));
		proxy.cancelOrder(new OrderRef("2"));
		assertNull(desk.handler(new DeskService()).handle(desk.exchange("cancelOrder").orElseThrow(),
				new OrderRef("3")));
		assertEquals(List.of("cancelOrder 1", "cancelOrder 2"), seen);
	}
}
