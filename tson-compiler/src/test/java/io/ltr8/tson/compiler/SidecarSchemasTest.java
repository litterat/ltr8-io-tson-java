package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Loads and resolves every {@code schemas/*.tn} file in the shared {@code ltr8-io-tson-test-suite}
 * checkout against the real bundled meta.tn/core.tn chain -- those are the real TSON schemas that
 * repo's own README uses to formally describe its sidecar format (replacing ad hoc BNF), so this is
 * exactly the kind of drift this whole exercise exists to catch: if a spec revision bump or a
 * resolver change ever breaks one of them, this fails loudly instead of the schema silently going
 * stale relative to the toolchain it's meant to be validated against.
 *
 * <p>Skipped via {@link Assumptions}, not failed, when no suite checkout is found -- same
 * convention, and the same {@link SuiteCheckout} search, {@link ConformanceSuiteTest} uses.
 */
class SidecarSchemasTest {

    private static final List<String> SCHEMA_FILES = List.of(
            "sidecar-common.tn", "lexer-sidecar.tn", "parser-sidecar.tn", "resolver-sidecar.tn",
            "vocabulary-sidecar.tn");

    /** The identity prefix the suite's own schemas are published under; anything else is a bundled schema. */
    private static final String SUITE_SCHEMA_PREFIX = "https://tson.io/test-suite/schemas/";

    @TestFactory
    Stream<DynamicTest> sidecarSchemasResolve() {
        SuiteCheckout.assumeAvailable();
        Path schemasRoot = SuiteCheckout.schemasRoot().orElseThrow();
        return SCHEMA_FILES.stream()
                .map(fileName -> DynamicTest.dynamicTest(fileName, () -> checkSchemaResolves(schemasRoot, fileName)));
    }

    private static void checkSchemaResolves(Path schemasRoot, String fileName) {
        String source = read(schemasRoot.resolve(fileName));
        SchemaDocument document = new TsonSchemaParser(source).parseSchemaDocument();

        // Same bootstrap sequence TsonConfig#build uses -- meta-kernel has to be resolved and
        // registered explicitly before anything that transitively !!imports it (meta.tn, here) can
        // register itself. A fresh registry per schema file, deliberately, so one file's own
        // failure doesn't leave a shared registry in a half-registered state for the next.
        TsonCompiledMetaRegistry compiledRegistry =
                new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext(), id -> fetch(schemasRoot, id));
        TsonCompiledSchemaLoader loader = compiledRegistry;
        SchemaDocument metaKernelDocument = new TsonSchemaParser(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID)).parseSchemaDocument();
        TsonSchema resolvedMetaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        compiledRegistry.register(resolvedMetaKernel, loader.loadMeta(TsonBundledSchemas.META_KERNEL_ID));

        TsonSchema resolved = new TsonSchemaResolver(loader).resolveSchema(document);
        assertFalse(resolved.entries().isEmpty(), fileName + " resolved with no entries");
    }

    /**
     * Serves the suite's own schemas beside the bundled ones. The layer schemas {@code !!import}
     * {@code sidecar-common.tn}, so a fetcher that only knows meta.tn/core.tn cannot resolve any of
     * them -- and the shared declarations they used to duplicate are exactly what that import
     * exists to stop.
     *
     * <p>An identity maps to a file name directly: these schemas are published under one prefix and
     * named after the file they live in, so there is nothing to look up.
     */
    private static String fetch(Path schemasRoot, String id) {
        if (id.startsWith(SUITE_SCHEMA_PREFIX)) {
            return read(schemasRoot.resolve(id.substring(SUITE_SCHEMA_PREFIX.length())));
        }
        return TsonBundledSchemas.fetch(id);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
