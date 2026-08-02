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
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Loads and resolves every {@code schemas/*.tn} file in the sibling {@code ltr8-io-tson-test-suite}
 * repo against the real bundled meta.tn/core.tn chain -- those are the real TSON schemas that
 * repo's own README uses to formally describe its sidecar format (replacing ad hoc BNF), so this is
 * exactly the kind of drift this whole exercise exists to catch: if a spec revision bump or a
 * resolver change ever breaks one of them, this fails loudly instead of the schema silently going
 * stale relative to the toolchain it's meant to be validated against.
 *
 * <p>Skipped via {@link Assumptions}, not failed, when the sibling repo isn't checked out -- same
 * convention {@link ConformanceSuiteTest} already uses.
 */
class SidecarSchemasTest {

    private static final Path SCHEMAS_ROOT =
            Paths.get("").toAbsolutePath().resolve("../../ltr8-io-tson-test-suite/schemas").normalize();

    private static final List<String> SCHEMA_FILES =
            List.of("lexer-sidecar.tn", "parser-sidecar.tn", "resolver-sidecar.tn", "vocabulary-sidecar.tn");

    @TestFactory
    Stream<DynamicTest> sidecarSchemasResolve() {
        Assumptions.assumeTrue(Files.isDirectory(SCHEMAS_ROOT),
                "ltr8-io-tson-test-suite not found at " + SCHEMAS_ROOT
                        + " (expected a sibling checkout) -- skipping sidecar schema checks");
        return SCHEMA_FILES.stream().map(fileName -> DynamicTest.dynamicTest(fileName, () -> checkSchemaResolves(fileName)));
    }

    private static void checkSchemaResolves(String fileName) {
        String source;
        try {
            source = Files.readString(SCHEMAS_ROOT.resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SchemaDocument document = new TsonSchemaParser(source).parseSchemaDocument();

        // Same bootstrap sequence TsonConfig#build uses -- meta-kernel has to be resolved and
        // registered explicitly before anything that transitively !!imports it (meta.tn, here) can
        // register itself. A fresh registry per schema file, deliberately, so one file's own
        // failure doesn't leave a shared registry in a half-registered state for the next.
        TsonCompiledSchemaRegistry compiledRegistry =
                new TsonCompiledSchemaRegistry(SchemaMetaNameBinder.defaultContext(), TsonBundledSchemas::fetch);
        TsonCompiledSchemaLoader loader = compiledRegistry;
        SchemaDocument metaKernelDocument = new TsonSchemaParser(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID)).parseSchemaDocument();
        TsonSchema resolvedMetaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        compiledRegistry.register(resolvedMetaKernel, loader.loadMeta(TsonBundledSchemas.META_KERNEL_ID));

        TsonSchema resolved = new TsonSchemaResolver(loader).resolveSchema(document);
        assertFalse(resolved.entries().isEmpty(), fileName + " resolved with no entries");
    }
}
