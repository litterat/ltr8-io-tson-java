package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.reader.Dom;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same proof {@link MetaSchemaImportTest} gives for meta.tn, one rung further up the schema
 * ladder: registers meta-kernel explicitly (its own well-known bootstrap case, per {@link
 * TsonCompiledMetaRegistry}'s own Javadoc), then loads meta.tn and {@code core.tn} through
 * the fully generic fetch-parse-resolve-register-compile path -- exactly the sequence {@link
 * TsonBundledSchemas}'s own class Javadoc documents as the intended way to load this library's
 * three bundled schema documents.
 *
 * <p>Every real {@code core.tn} declaration resolves in a single source-order pass, the same way
 * meta.tn's own 31 do -- core.tn's own declaration order already places each dependency before its
 * use, so no {@code MetaKernelBootstrapResolver}-style two-pass ordering is needed here either.
 */
class CoreSchemaImportTest {

    /**
     * The registered {@link TsonSchemaRegistry} plus the exact {@link TsonCompiledMetaRegistry}
     * that loaded everything into it -- a second call reusing this same loader hits {@code
     * TsonCompiledMetaRegistry}'s own cache (see {@link TsonCompiledMetaRegistry#load}'s own case
     * 1) rather than attempting to register {@code core.tn} a second time, which {@link
     * TsonSchemaRegistry#register} would correctly reject as a duplicate identity.
     */
    private record Loaded(TsonSchemaRegistry schemaRegistry, TsonCompiledMetaRegistry registry) {
    }

    private static Loaded loadMetaKernelMetaAndCore() {
        TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
        TsonCompiledMetaRegistry registry = new TsonCompiledMetaRegistry(schemaRegistry, SchemaMetaNameBinder.defaultContext(), TsonBundledSchemas::fetch);
        TsonCompiledSchemaLoader loader = registry;

        // meta.tn's own !!import needs meta-kernel present in the *shared* registry first --
        // meta-kernel's own bootstrap case (loader.load(META_KERNEL_ID)) is never cached in registry
        // itself (see TsonCompiledMetaRegistry's own Javadoc), so it's registered separately,
        // resolved ordinarily against this same loader (whose own bootstrap branch supplies the
        // structure namespace).
        SchemaDocument metaKernelDocument = new TsonSchemaParser(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID)).parseSchemaDocument();
        TsonSchema resolvedMetaKernel = new SchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(resolvedMetaKernel, loader.loadMeta(TsonBundledSchemas.META_KERNEL_ID));

        loader.loadMeta(TsonBundledSchemas.META_ID);
        loader.resolveLinked(TsonBundledSchemas.CORE_ID); // core.tn is a non-meta import: resolved, not compiled here

        return new Loaded(schemaRegistry, registry);
    }

    @Test
    void resolvesAndRegistersEveryRealCoreTn1Declaration() {
        TsonSchemaRegistry schemaRegistry = loadMetaKernelMetaAndCore().schemaRegistry();

        Optional<TsonLinkedSchema> registered = schemaRegistry.get(TsonBundledSchemas.CORE_ID);
        assertTrue(registered.isPresent(), "expected core.tn to be registered");

        TsonSchema core = registered.get().schema();
        assertEquals(48, core.entries().size(), "expected every core.tn declaration to resolve");

        // A representative spread of core.tn's own real declarations -- atom refinements
        // (int32/positive_integer) and constructor applications (hex, float32, cidr4, ipv4, complex,
        // unknown) -- all genuinely present in the validated, registered namespace. core.tn declares
        // no !!import of its own (only !!meta:"...meta.tn"), so unlike MetaSchemaImportTest's own
        // assertions, meta.tn's/meta-kernel's own vocabulary (e.g. "atom", "binary") is never merged
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
     * {@link TsonCompiledMetaRegistry#register} (reached via {@code loader.load}, inside {@link
     * #loadMetaKernelMetaAndCore}) already compiled every one of core.tn's own 48 entries as a side
     * effect of registering it -- but {@link TsonSchemaCompiler}'s own per-entry build-failure
     * deferral means a broken entry wouldn't have failed that step; it would silently have compiled to
     * an {@code ErrorReader} instead (see that class's own Javadoc), only throwing once someone
     * actually tries to {@code read} it. This confirms exactly which entries land there and pins the
     * set down: {@code unknown} alone -- constructed via {@code unknown_type}, one of the two constructors
     * {@link ValueReaderFactoryRegistry} registers to {@code ErrorReader} outright (the other, {@code
     * extern}, has no core.tn declaration at all) -- a real, already-documented, deliberate gap (see this
     * repo's own CLAUDE.md, "Not yet implemented"), not a regression to chase. Every *other* entry compiles
     * to a genuinely usable reader.
     */
    @Test
    void exactlyTheUnknownAtomConstructorCompilesToAnErrorReader() {
        Loaded loaded = loadMetaKernelMetaAndCore();
        TsonSchema core = loaded.schemaRegistry().get(TsonBundledSchemas.CORE_ID).orElseThrow().schema();

        // core.tn is not a meta-layer schema (its !!meta is meta.tn, not meta-kernel), so it's resolved
        // but never compiled in the core -- its readers are compiled per mode in a read registry, exactly
        // as they are when a user schema imports it. DOM mode is enough to check which entries error.
        TsonCompiledSchema compiledCore =
                TsonCompiledSchemaRegistry.tree(loaded.registry()).get(TsonBundledSchemas.CORE_ID);

        Set<String> errored = new TreeSet<>();
        for (String name : core.entries().keySet()) {
            TsonTypeReader<?> reader = compiledCore.get(name);
            if (reader.getClass().getSimpleName().equals("ErrorReader")) {
                errored.add(name);
            }
        }

        assertEquals(Set.of("unknown"), errored);
    }

    /**
     * The compiled-reader half of the entry above: {@code cidr4}/{@code cidr6} don't merely stop being
     * {@code ErrorReader}s, they read real values against core.tn's own declarations -- the schema-driven
     * path, next to {@code TsonObjectReaderTest}'s coverage of the schemaless one.
     */
    @Test
    void theCidrEntriesReadRealValuesAgainstCoreTnsOwnDeclarations() {
        Loaded loaded = loadMetaKernelMetaAndCore();
        TsonCompiledSchema compiledCore =
                TsonCompiledSchemaRegistry.tree(loaded.registry()).get(TsonBundledSchemas.CORE_ID);

        assertEquals("10.0.0.0/8",
                Dom.of((TsonValue) compiledCore.get("cidr4").read(TestDocuments.document("\"10.0.0.0/8\""))));
        assertEquals("2001:db8::/32",
                Dom.of((TsonValue) compiledCore.get("cidr6").read(TestDocuments.document("\"2001:db8::/32\""))));
    }
}
