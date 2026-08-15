package io.ltr8.tson.compiler.resolver;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-error schema resolution ([TSON-DATA] §8.1: "SHOULD continue processing after an error to report
 * multiple issues in a single pass", and MUST carry source position in all error reports).
 *
 * <p>The fail-fast overloads are unchanged and still throw at the first problem; only {@code
 * resolveSchema(document, positions, receiver)} collects. What these tests pin is the part that is easy to
 * get subtly wrong: resolution follows *dependencies*, not source order, so a declaration reached as another
 * one's supertype fails inside a nested call. Reporting has to attribute that to the declaration that
 * actually failed, and exactly once, even though the driving loop reaches it again afterwards.
 */
class SchemaResolverDiagnosticsTest {

    private static final String ID = "https://example.test/broken.tn";

    /**
     * Four independently broken declarations plus one good one, each failing a different rule so that no one
     * is a consequence of another: two refinements that widen rather than narrow (§5.7), a record body
     * declaring the same field name twice (§5.8), and a refinement source that names nothing at all (§3.3.1)
     * -- the plain typo, and the most common of the four in practice.
     */
    private static final String FOUR_BROKEN = """
            !!id:"https://example.test/broken.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              widens => !uint8 ^ { min: -10  max: 300 }
              fine => int32
              widens_too => !int32 ^ { max: 99999999999 }
              declared_twice => { value: int32  value: int32 }
              typo_in_source => !no_such_atom ^ { max: 1 }
            }
            """;

    private static TsonCompiledSchemaLoader standardLibrary() {
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledMetaRegistry registry = new TsonCompiledMetaRegistry(context, TsonBundledSchemas::fetch);
        TsonCompiledSchemaLoader loader = registry;

        SchemaDocument metaKernel = new TsonSchemaParser(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID)).parseSchemaDocument();
        registry.register(new SchemaResolver(loader).resolveSchema(metaKernel),
                loader.loadMeta(TsonBundledSchemas.META_KERNEL_ID));
        loader.loadMeta(TsonBundledSchemas.META_ID);
        loader.resolveLinked(TsonBundledSchemas.CORE_ID);
        return loader;
    }

    private static List<Diagnostic> resolveCollecting(String schemaText) {
        TsonSchemaParser parser = new TsonSchemaParser(schemaText);
        SchemaDocument document = parser.parseSchemaDocument();
        TsonDiagnosticsCollector collector = new TsonDiagnosticsCollector();
        TsonSchema resolved = new SchemaResolver(standardLibrary())
                .resolveSchema(document, parser.declarationPositions(), collector);
        // Even a schema that failed comes back whole -- placeholders stand in for the broken entries, which is
        // what lets the good ones resolve rather than the run stopping at the first bad one.
        assertNotNull(resolved);
        assertTrue(resolved.entries().containsKey("fine"), "a good declaration still resolves alongside broken ones");
        return collector.diagnostics();
    }

    @Test
    void everyBrokenDeclarationIsReportedInOnePass() {
        List<Diagnostic> diagnostics = resolveCollecting(FOUR_BROKEN);

        assertEquals(4, diagnostics.size(), () -> "expected one diagnostic per broken declaration, got " + diagnostics);
    }

    @Test
    void eachDiagnosticNamesItsOwnDeclarationAndSchema() {
        List<Diagnostic> diagnostics = resolveCollecting(FOUR_BROKEN);

        assertEquals(List.of("/declared_twice", "/typo_in_source", "/widens", "/widens_too"),
                diagnostics.stream().map(Diagnostic::schemaPointer).sorted().toList());
        for (Diagnostic diagnostic : diagnostics) {
            assertEquals("example.test/broken.tn", diagnostic.schemaId());
            assertEquals(Diagnostic.Code.SCHEMA_ERROR, diagnostic.code());
        }
    }

    /** §8.1's MUST: source position in *all* error reports. Before this, every schema-side error had none. */
    @Test
    void everyDiagnosticCarriesTheDeclarationsPositionInTheSchemaSource() {
        for (Diagnostic diagnostic : resolveCollecting(FOUR_BROKEN)) {
            assertTrue(diagnostic.schemaPosition().isPresent(),
                    () -> "no schema position on " + diagnostic.schemaPointer());
            assertTrue(diagnostic.schemaPosition().get().line() > 0);
        }
    }

    /** The data end is empty: this problem is in a schema, and no document has been read against it. */
    @Test
    void aSchemaDiagnosticCarriesNoDataLocation() {
        for (Diagnostic diagnostic : resolveCollecting(FOUR_BROKEN)) {
            assertEquals("", diagnostic.path());
            assertTrue(diagnostic.dataPosition().isEmpty());
        }
    }

    /**
     * The case the catch placement exists for. {@code child} is resolved *twice over*: once on demand, as
     * {@code parent}'s supertype, and once when the driving loop reaches it in its own right. Catching around
     * that loop instead of inside the memoized getter would report it twice -- and would blame {@code parent}
     * the first time.
     */
    @Test
    void aDeclarationThatFailsWhileNestedIsReportedOnceAndAgainstItself() {
        List<Diagnostic> diagnostics = resolveCollecting("""
                !!id:"https://example.test/broken.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  child => !uint8 ^ { min: -10 }
                  parent => child & { extra: int32 }
                  fine => int32
                }
                """);

        assertEquals(List.of("/child"), diagnostics.stream().map(Diagnostic::schemaPointer).toList());
    }

    /** The default overloads keep throwing, so nothing that exists today changes behaviour. */
    @Test
    void withoutAReceiverTheFirstFailureStillThrows() {
        TsonSchemaParser parser = new TsonSchemaParser(FOUR_BROKEN);
        SchemaDocument document = parser.parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver(standardLibrary());

        assertThrows(TsonSchemaValidationException.class,
                () -> resolver.resolveSchema(document, parser.declarationPositions()));
    }
}
