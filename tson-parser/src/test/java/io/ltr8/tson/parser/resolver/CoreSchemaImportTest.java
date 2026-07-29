package io.ltr8.tson.parser.resolver;

import io.ltr8.tson.parser.TsonSchemaParser;
import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.parser.compiler.ValueReaderFactoryRegistry;
import io.ltr8.tson.parser.config.BundledSchemaSource;
import io.ltr8.tson.parser.config.SchemaMetaNameBinder;
import io.ltr8.tson.parser.config.TsonCompiledRegistry;
import io.ltr8.tson.parser.config.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same proof {@link MetaSchemaImportTest} gives for meta.tn1, one rung further up the schema
 * ladder: registers meta-kernel explicitly (its own well-known bootstrap case, per {@link
 * DefaultTsonCompiledSchemaLoader}'s own Javadoc), then loads meta.tn1 and {@code core.tn1} through
 * the fully generic fetch-parse-resolve-register-compile path -- exactly the sequence {@link
 * BundledSchemaSource}'s own class Javadoc documents as the intended way to load this library's
 * three bundled schema documents.
 *
 * <p>Every real {@code core.tn1} declaration resolves in a single source-order pass, the same way
 * meta.tn1's own 31 do -- core.tn1's own declaration order already places each dependency before its
 * use, so no {@code MetaKernelBootstrapResolver}-style two-pass ordering is needed here either.
 */
class CoreSchemaImportTest {

    /**
     * The registered {@link TsonSchemaRegistry} plus the exact {@link DefaultTsonCompiledSchemaLoader}
     * that loaded everything into it -- a second call reusing this same loader hits {@code
     * TsonCompiledRegistry}'s own cache (see {@link DefaultTsonCompiledSchemaLoader#load}'s own case
     * 1) rather than attempting to register {@code core.tn1} a second time, which {@link
     * TsonSchemaRegistry#register} would correctly reject as a duplicate identity.
     */
    private record Loaded(TsonSchemaRegistry schemaRegistry, DefaultTsonCompiledSchemaLoader loader) {
    }

    private static Loaded loadMetaKernelMetaAndCore() {
        TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
        ValueReaderFactoryResolver resolver = ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext());
        TsonCompiledRegistry registry = new TsonCompiledRegistry(schemaRegistry, resolver);
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry, BundledSchemaSource.INSTANCE);

        // meta.tn1's own !!import needs meta-kernel present in the *shared* registry first --
        // meta-kernel's own bootstrap case (loader.load(META_KERNEL_ID)) is never cached in registry
        // itself (see DefaultTsonCompiledSchemaLoader's own Javadoc), so it's registered separately,
        // resolved ordinarily against this same loader (whose own bootstrap branch supplies the
        // structure namespace).
        SchemaDocument metaKernelDocument = new TsonSchemaParser(
                BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID)).parseSchemaDocument();
        TsonSchema resolvedMetaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(resolvedMetaKernel, loader.load(BundledSchemaSource.META_KERNEL_ID));

        loader.load(BundledSchemaSource.META_TN1_ID);
        loader.load(BundledSchemaSource.CORE_TN1_ID); // needs meta.tn1 registered first, same reasoning

        return new Loaded(schemaRegistry, loader);
    }

    @Test
    void resolvesAndRegistersEveryRealCoreTn1Declaration() {
        TsonSchemaRegistry schemaRegistry = loadMetaKernelMetaAndCore().schemaRegistry();

        Optional<TsonLinkedSchema> registered = schemaRegistry.get(BundledSchemaSource.CORE_TN1_ID);
        assertTrue(registered.isPresent(), "expected core.tn1 to be registered");

        TsonSchema core = registered.get().schema();
        assertEquals(48, core.entries().size(), "expected every core.tn1 declaration to resolve");

        // A representative spread of core.tn1's own real declarations -- atom refinements
        // (int32/positive_integer) and constructor applications (hex, float32, cidr4, ipv4, complex,
        // unknown) -- all genuinely present in the validated, registered namespace. core.tn1 declares
        // no !!import of its own (only !!meta:"...meta.tn1"), so unlike MetaSchemaImportTest's own
        // assertions, meta.tn1's/meta-kernel's own vocabulary (e.g. "atom", "binary") is never merged
        // into this schema's own entries() -- it's only reachable one hop via !!meta, and only for a
        // "source" reference (§3.3.1's structure-namespace rule), which is exactly what lets int32's
        // own `source: integer_type` validate despite integer_type living in meta-kernel, two hops up.
        assertTrue(core.entries().containsKey("int32"));
        assertTrue(core.entries().containsKey("positive_integer"));
        assertTrue(core.entries().containsKey("hex"));
        assertTrue(core.entries().containsKey("float32"));
        assertTrue(core.entries().containsKey("float64"));
        assertTrue(core.entries().containsKey("cidr4"));
        assertTrue(core.entries().containsKey("cidr6"));
        assertTrue(core.entries().containsKey("email"));
        assertTrue(core.entries().containsKey("mac"));
        assertTrue(core.entries().containsKey("ipv4"));
        assertTrue(core.entries().containsKey("ipv6"));
        assertTrue(core.entries().containsKey("complex"));
        assertTrue(core.entries().containsKey("unknown"));
    }

    /**
     * {@link TsonCompiledRegistry#register} (reached via {@code loader.load}, inside {@link
     * #loadMetaKernelMetaAndCore}) already compiled every one of core.tn1's own 48 entries as a side
     * effect of registering it -- but {@link TsonSchemaCompiler}'s own per-entry build-failure
     * deferral means a broken entry wouldn't have failed that step; it would silently have compiled to
     * an {@code ErrorReader} instead (see that class's own Javadoc), only throwing once someone
     * actually tries to {@code read} it. This confirms exactly which entries land there and pins the
     * set down: {@code cidr4}/{@code cidr6}/{@code email}/{@code mac}/{@code unknown} -- constructed
     * via {@code cidr4_type}/{@code cidr6_type}/{@code email_type}/{@code mac_type}/{@code
     * unknown_type}, five of the six constructors {@link ValueReaderFactoryRegistry} registers to
     * {@code ErrorReader} outright (the sixth, {@code extern}, has no core.tn1 declaration at all) --
     * a real, already-documented, deliberate gap (see this repo's own CLAUDE.md, "Not yet
     * implemented"), not a regression to chase. Every *other* entry compiles to a genuinely usable
     * reader.
     */
    @Test
    void exactlyTheFiveUndocumentedAtomConstructorsCompileToErrorReaders() {
        Loaded loaded = loadMetaKernelMetaAndCore();
        TsonSchema core = loaded.schemaRegistry().get(BundledSchemaSource.CORE_TN1_ID).orElseThrow().schema();

        // A cache hit on TsonCompiledRegistry's own store -- core.tn1 was already compiled inside
        // loadMetaKernelMetaAndCore, this just fetches that same TsonCompiledMetaSchema back.
        TsonCompiledMetaSchema compiledCore = loaded.loader().load(BundledSchemaSource.CORE_TN1_ID);

        Set<String> errored = new TreeSet<>();
        for (String name : core.entries().keySet()) {
            TsonValueReader<?> reader = compiledCore.compiledSchema().get(name);
            if (reader.getClass().getSimpleName().equals("ErrorReader")) {
                errored.add(name);
            }
        }

        assertEquals(Set.of("cidr4", "cidr6", "email", "mac", "unknown"), errored);
    }
}
