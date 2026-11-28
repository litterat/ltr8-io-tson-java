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

import io.ltr8.annotation.Atom;
import io.ltr8.bind.DataBindContext;
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
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A binding run with {@code io.ltr8.bind} itself a named module, as on an application's module path. Bind's tests run
 * on the class path, where its unnamed module reads every module, so a member found through bind's own lookup is
 * reached there and refused here: a named {@code io.ltr8.bind} reads no user module. Bind, its annotations and a user
 * module are loaded in a layer of their own, over the platform class loader, and the user module drives the binding.
 */
class NamedModuleBindingTest {

	private static final Map<String, String> SOURCES = Map.ofEntries(
			Map.entry("module-info.java", """
					module shelf.app {
					    requires io.ltr8.bind;
					    exports shelf.api;
					}
					"""),
			Map.entry("shelf/api/Item.java", "package shelf.api; public record Item(String name, int count) { }"),
			Map.entry("shelf/api/Point.java", """
					package shelf.api;
					@io.ltr8.annotation.Tuple
					public record Point(int x, int y) { }
					"""),
			Map.entry("shelf/api/Code.java", """
					package shelf.api;
					import io.ltr8.annotation.Atom;
					@Atom
					public final class Code {
					    private final String text;
					    @Atom
					    public Code(String text) { this.text = text; }
					    @Atom
					    public String toData() { return text; }
					    @Override public boolean equals(Object o) { return o instanceof Code c && c.text.equals(text); }
					    @Override public int hashCode() { return text.hashCode(); }
					}
					"""),
			Map.entry("shelf/api/Bag.java", """
					package shelf.api;
					public final class Bag<T> extends java.util.ArrayList<T> {
					    public Bag() { }
					}
					"""),
			Map.entry("shelf/api/Bagged.java", "package shelf.api; public record Bagged(Bag<String> items) { }"),
			Map.entry("shelf/api/Index.java", """
					package shelf.api;
					public final class Index<K, V> extends java.util.LinkedHashMap<K, V> {
					    public Index() { }
					}
					"""),
			Map.entry("shelf/api/Indexed.java",
					"package shelf.api; public record Indexed(Index<String, Integer> entries) { }"),
			Map.entry("shelf/hidden/Secrets.java", """
					package shelf.hidden;
					public final class Secrets<T> extends java.util.ArrayList<T> {
					    public Secrets() { }
					}
					"""),
			Map.entry("shelf/api/Kept.java",
					"package shelf.api; public record Kept(shelf.hidden.Secrets<String> secrets) { }"),
			Map.entry("shelf/api/Drive.java", """
					package shelf.api;
					import io.ltr8.bind.DataBindContext;
					import io.ltr8.bind.DataClass;
					import io.ltr8.bind.mapper.MapMapper;
					public final class Drive {
					    public static Object sample(String name) {
					        return switch (name) {
					            case "item" -> new Item("pen", 3);
					            case "point" -> new Point(1, 2);
					            case "code" -> new Code("X-1");
					            case "bag" -> {
					                Bag<String> bag = new Bag<>();
					                bag.add("a");
					                bag.add("b");
					                yield new Bagged(bag);
					            }
					            case "index" -> {
					                Index<String, Integer> index = new Index<>();
					                index.put("a", 1);
					                index.put("b", 2);
					                yield new Indexed(index);
					            }
					            case "kept" -> new Kept(new shelf.hidden.Secrets<>());
					            default -> throw new IllegalArgumentException(name);
					        };
					    }
					    /** The data form of the sample, which is read back and must equal it. */
					    public static Object roundTrip(String name) throws Exception {
					        Object value = sample(name);
					        DataBindContext context = DataBindContext.builder().build();
					        DataClass dataClass = context.getDescriptor(value.getClass());
					        MapMapper mapper = new MapMapper(context);
					        Object data = mapper.toMap(dataClass, value);
					        Object back = mapper.toObject(dataClass, data);
					        if (!back.equals(value)) {
					            throw new AssertionError(back + " is read back for " + value);
					        }
					        return data;
					    }
					}
					"""));

	private static ModuleLayer layer;

	private static Class<?> drive;

	@BeforeAll
	static void compileAndLoad(@TempDir Path dir) throws IOException, URISyntaxException, ClassNotFoundException {
		Path bind = location(DataBindContext.class);
		Path annotation = location(Atom.class);
		Path source = dir.resolve("src");
		Path out = dir.resolve("out");
		List<String> arguments = new ArrayList<>(List.of("-d", out.toString(), "--module-path",
				bind + java.io.File.pathSeparator + annotation));
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
		Configuration configuration = boot.configuration().resolve(ModuleFinder.of(out, bind, annotation),
				ModuleFinder.of(), Set.of("shelf.app"));
		// Over the platform loader, so no class of bind's is found on the class path the tests run on.
		layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader());
		drive = Class.forName("shelf.api.Drive", true, layer.findLoader("shelf.app"));
	}

	@Test
	void bindIsANamedModuleThatReadsNoUserModule() {
		Module bind = layer.findModule("io.ltr8.bind").orElseThrow();
		Module shelf = drive.getModule();

		assertTrue(bind.isNamed());
		assertEquals(layer, bind.getLayer());
		assertEquals("shelf.app", shelf.getName());
		assertTrue(shelf.isExported("shelf.api"));
		assertFalse(bind.canRead(shelf));
	}

	@Test
	void aRecordRoundTrips() throws Exception {
		assertEquals(Map.of("name", "pen", "count", 3), roundTrip("item"));
	}

	@Test
	void aTupleRoundTrips() throws Exception {
		assertEquals(List.of(1, 2), roundTrip("point"));
	}

	@Test
	void anAtomClassRoundTrips() throws Exception {
		assertEquals("X-1", roundTrip("code"));
	}

	@Test
	void aCollectionClassRoundTrips() throws Exception {
		assertEquals(Map.of("items", List.of("a", "b")), roundTrip("bag"));
	}

	@Test
	void aMapClassRoundTrips() throws Exception {
		assertEquals(Map.of("entries", Map.of("a", 1, "b", 2)), roundTrip("index"));
	}

	@Test
	void aCollectionClassInAPackageTheModuleDoesNotExportIsRefusedNamingIt() {
		Exception refused = assertThrows(Exception.class, () -> roundTrip("kept"));

		List<String> messages = new ArrayList<>();
		for (Throwable at = refused; at != null; at = at.getCause()) {
			messages.add(at.getMessage());
		}
		assertEquals(List.of("shelf.hidden.Secrets is bound through a member that is not accessible: constructor "
						+ "shelf.hidden.Secrets(), of a class the public lookup cannot access",
				"symbolic reference class is not accessible: class shelf.hidden.Secrets, from public Lookup"),
				messages);
	}

	/** The sample's data form, with each array as a list. */
	private static Object roundTrip(String sample) throws Exception {
		try {
			return listed(drive.getMethod("roundTrip", String.class).invoke(null, sample));
		} catch (InvocationTargetException e) {
			throw e.getCause() instanceof Exception cause ? cause : e;
		}
	}

	private static Object listed(Object data) {
		return switch (data) {
			case Object[] array -> Arrays.stream(array).map(NamedModuleBindingTest::listed).toList();
			case Map<?, ?> map -> {
				Map<Object, Object> listed = new java.util.LinkedHashMap<>();
				map.forEach((key, value) -> listed.put(key, listed(value)));
				yield listed;
			}
			default -> data;
		};
	}

	private static Path location(Class<?> type) throws URISyntaxException {
		return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
	}
}
