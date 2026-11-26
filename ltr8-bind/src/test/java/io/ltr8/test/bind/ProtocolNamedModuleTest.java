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
import io.ltr8.bind.mapper.MapMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.ltr8.test.bind.ProtocolRefusalTest.assertNames;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A protocol under a named module. The handles come from the public lookup, so an interface and the records it
 * names work from a package their module exports to everyone, and are refused from one it does not export or
 * exports to named modules only. The module is compiled here and loaded in a layer of its own, since bind's tests
 * run on the class path.
 */
class ProtocolNamedModuleTest {

	private static final Map<String, String> SOURCES = Map.of(
			"module-info.java", """
					module orders.app {
					    exports orders.api;
					    exports orders.qualified to io.ltr8.bind;
					}
					""",
			"orders/api/NewOrder.java", "package orders.api; public record NewOrder(String sku, int quantity) { }",
			"orders/api/Order.java", "package orders.api; public record Order(String sku, int quantity) { }",
			"orders/api/Orders.java", """
					package orders.api;
					public interface Orders {
					    Order placeOrder(NewOrder request);
					    static Orders service() { return new orders.impl.Service(); }
					}
					""",
			"orders/api/Leaky.java", """
					package orders.api;
					public interface Leaky { orders.hidden.Secret reveal(NewOrder request); }
					""",
			"orders/impl/Service.java", """
					package orders.impl;
					import orders.api.*;
					public final class Service implements Orders {
					    public Order placeOrder(NewOrder request) {
					        return new Order(request.sku(), request.quantity() * 10);
					    }
					}
					""",
			"orders/hidden/Hidden.java", """
					package orders.hidden;
					public interface Hidden { orders.api.Order placeOrder(orders.api.NewOrder request); }
					""",
			"orders/hidden/Secret.java", "package orders.hidden; public record Secret(String value) { }",
			"orders/qualified/Qualified.java", """
					package orders.qualified;
					public interface Qualified { orders.api.Order placeOrder(orders.api.NewOrder request); }
					""");

	private static ClassLoader loader;

	private final DataBindContext context = DataBindContext.builder().build();

	@BeforeAll
	static void compileAndLoad(@TempDir Path dir) throws IOException {
		Path source = dir.resolve("src");
		Path out = dir.resolve("out");
		List<String> arguments = new ArrayList<>(List.of("-d", out.toString()));
		for (Map.Entry<String, String> file : SOURCES.entrySet()) {
			Path path = source.resolve(file.getKey());
			Files.createDirectories(path.getParent());
			Files.writeString(path, file.getValue());
			arguments.add(path.toString());
		}
		JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
		ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
		int status = javac.run(null, null, new PrintStream(diagnostics, true, StandardCharsets.UTF_8),
				arguments.toArray(String[]::new));
		assertEquals(0, status, diagnostics.toString(StandardCharsets.UTF_8));

		ModuleLayer boot = ModuleLayer.boot();
		Configuration configuration = boot.configuration().resolve(ModuleFinder.of(out), ModuleFinder.of(),
				Set.of("orders.app"));
		ModuleLayer layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
		loader = layer.findLoader("orders.app");
	}

	@Test
	void theClassesAreInANamedModuleAndBindIsNot() throws ClassNotFoundException {
		Module module = load("orders.api.Orders").getModule();

		assertTrue(module.isNamed());
		assertEquals("orders.app", module.getName());
		assertTrue(module.isExported("orders.api"));
		assertFalse(module.isExported("orders.hidden"));
		assertFalse(module.isExported("orders.qualified"));
		assertFalse(DataProtocol.class.getModule().isNamed(), "bind's tests run on the class path");
	}

	@Test
	void anInterfaceAndItsRecordsInAnExportedPackageWorkThroughThePublicLookup() throws Throwable {
		Class<?> orders = load("orders.api.Orders");
		DataProtocol<?> protocol = context.getProtocol(orders);
		DataExchange placeOrder = protocol.exchange("place_order").orElseThrow();
		MapMapper mapper = new MapMapper(context);

		Object request = mapper.toObject(placeOrder.request().orElseThrow(), Map.of("sku", "A-1", "quantity", 2));
		Object target = orders.getMethod("service").invoke(null);
		Object response = handle(protocol, target, placeOrder, request);

		assertEquals(load("orders.api.Order"), response.getClass());
		assertEquals(Map.of("sku", "A-1", "quantity", 20), mapper.toMap(placeOrder.response().orElseThrow(), response));
	}

	@Test
	void theTargetsClassMayBeInAPackageTheModuleDoesNotExport() throws Exception {
		Object target = load("orders.api.Orders").getMethod("service").invoke(null);

		assertFalse(target.getClass().getModule().isExported(target.getClass().getPackageName()));
	}

	@Test
	void anInterfaceInAPackageTheModuleDoesNotExportIsRefused() throws ClassNotFoundException {
		Class<?> hidden = load("orders.hidden.Hidden");

		DataBindException refused = assertThrows(DataBindException.class, () -> context.getProtocol(hidden));
		assertNames(refused.getMessage(), "orders.hidden.Hidden", "public lookup",
				"package orders.hidden is not exported by module orders.app");
	}

	@Test
	void anInterfaceInAPackageExportedToBindAloneIsRefused() throws ClassNotFoundException {
		Class<?> qualified = load("orders.qualified.Qualified");

		DataBindException refused = assertThrows(DataBindException.class, () -> context.getProtocol(qualified));
		assertNames(refused.getMessage(), "orders.qualified.Qualified", "public lookup",
				"package orders.qualified is not exported by module orders.app");
	}

	@Test
	void anExportedInterfaceNamingAClassInAPackageTheModuleDoesNotExportIsRefused() throws ClassNotFoundException {
		Class<?> leaky = load("orders.api.Leaky");

		DataBindException refused = assertThrows(DataBindException.class, () -> context.getProtocol(leaky));
		assertNames(refused.getMessage(), "orders.api.Leaky.reveal's response", "orders.hidden.Secret",
				"package orders.hidden is not exported by module orders.app");
	}

	private static Class<?> load(String name) throws ClassNotFoundException {
		return Class.forName(name, true, loader);
	}

	private static <T> Object handle(DataProtocol<T> protocol, Object target, DataExchange exchange, Object request)
			throws Throwable {
		return protocol.handler(protocol.typeClass().cast(target)).handle(exchange, request);
	}
}
