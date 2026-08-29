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
import java.util.Optional;

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
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
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
                .resolveSchema(document, parser.schemaPositions(), collector);
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
                diagnostics.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
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
                    () -> "no schema position on " + diagnostic.schemaPointer().orElseThrow());
            assertTrue(diagnostic.schemaPosition().get().line() > 0);
        }
    }

    /** The data end is empty: this problem is in a schema, and no document has been read against it. */
    @Test
    void aSchemaDiagnosticCarriesNoDataLocation() {
        for (Diagnostic diagnostic : resolveCollecting(FOUR_BROKEN)) {
            assertEquals(Optional.empty(), diagnostic.path());
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
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  child => !uint8 ^ { min: -10 }
                  parent => child & { extra: int32 }
                  fine => int32
                }
                """);

        assertEquals(List.of("/child"), diagnostics.stream().map(d -> d.schemaPointer().orElseThrow()).toList());
    }

    /**
     * A body the constructor's own vocabulary rejects reaches the resolver as a {@code TsonReadException}
     * from the governing meta's compiled reader, one layer below every other error here. It is collected
     * like the rest only because {@code DefinitionResolver} restates it as a schema error first: while it
     * wore an {@code UnsupportedOperationException}, one such declaration aborted the entire run -- no
     * diagnostics at all, exit 70, and a stack trace under a "this is a bug in tson" banner.
     */
    @Test
    void aBodyTheConstructorsVocabularyRejectsJoinsTheOtherDiagnostics() {
        List<Diagnostic> diagnostics = resolveCollecting("""
                !!id:"https://example.test/broken.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  bad_min => !integer ^ { min: "abc" }
                  bad_max => !integer ^ { max: "xyz" }
                  fine => int32
                }
                """);

        assertEquals(List.of("/bad_max", "/bad_min"),
                diagnostics.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
        for (Diagnostic diagnostic : diagnostics) {
            assertTrue(diagnostic.message().contains("not valid data for 'integer_type'"), diagnostic.message());
            assertTrue(diagnostic.schemaPosition().isPresent(), diagnostic.schemaPointer().orElseThrow());
        }
    }

    // ── Desugaring (§5.3's sugar forms), which runs inside resolveSchema and reports through the same
    //    receiver ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Both sugar forms the desugar phase can reject, in one schema, alongside a good declaration. Neither is
     * a consequence of the other: a vacuous floor of zero (§5.3's resolver error) and a size range no
     * array can satisfy (§5.3's {@code min_items <= max_items}, deferred to the materialising application by
     * §8.2). Before this, the first of them aborted the run.
     *
     * <p>Both are written at declaration position here because that is the only position they have. A size
     * specifier is declaration-level-only syntax and the parser rejects it at an inline type-ref position
     * outright ({@code TsonSchemaParserTest.inlineSizeSpecifierIsAParseError}), so per-declaration attribution
     * is exact for these two rather than an approximation forced by the granularity ceiling.
     */
    @Test
    void everyInvalidSugarFormIsReportedInOnePass() {
        List<Diagnostic> diagnostics = resolveCollecting("""
                !!id:"https://example.test/broken.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  vacuous => [int32; 0..]
                  inverted => [int32; 5..3]
                  fine => int32
                }
                """);

        assertEquals(List.of("/inverted", "/vacuous"),
                diagnostics.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
        for (Diagnostic diagnostic : diagnostics) {
            assertEquals("example.test/broken.tn", diagnostic.schemaId());
            assertEquals(Diagnostic.Code.SCHEMA_ERROR, diagnostic.code());
            assertTrue(diagnostic.schemaPosition().isPresent(), diagnostic.schemaPointer().orElseThrow());
        }
    }

    /**
     * The point of moving desugaring onto the receiver: a sugar-form error and a resolution error are
     * independent problems in different phases of the same call, and an author now sees both. Previously the
     * sugar form threw before {@code widens} was ever attempted, so fixing it revealed a second error that
     * had been there all along.
     */
    @Test
    void aSugarFormErrorAndAResolutionErrorAreBothReported() {
        List<Diagnostic> diagnostics = resolveCollecting("""
                !!id:"https://example.test/broken.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  vacuous => [int32; 0..]
                  widens => !uint8 ^ { min: -10  max: 300 }
                  fine => int32
                }
                """);

        assertEquals(List.of("/vacuous", "/widens"),
                diagnostics.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
    }

    /**
     * The {@code ABSORBED} placeholder's whole job. {@code user} composes with a declaration whose sugar form
     * failed; it must resolve cleanly against the empty-record stand-in rather than reporting a second
     * problem that is purely a consequence of the first. Passing the declaration through un-expanded instead
     * would hand {@code DefinitionResolver} the {@code ContainerTypeDef} the phase exists to remove, and that
     * raises an {@code UnsupportedOperationException} the resolver deliberately does not catch -- turning a
     * reported author error into an unreported abort.
     */
    @Test
    void aDependentOfAFailedSugarFormDoesNotReportAConsequence() {
        List<Diagnostic> diagnostics = resolveCollecting("""
                !!id:"https://example.test/broken.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  vacuous => [int32; 0..]
                  user => vacuous & { extra: int32 }
                  fine => int32
                }
                """);

        assertEquals(List.of("/vacuous"), diagnostics.stream().map(d -> d.schemaPointer().orElseThrow()).toList());
    }

    /** Desugaring's half of the same guarantee -- the overload without a receiver still throws its own error. */
    @Test
    void withoutAReceiverAnInvalidSugarFormStillThrows() {
        TsonSchemaParser parser = new TsonSchemaParser("""
                !!id:"https://example.test/broken.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  vacuous => [int32; 0..]
                  fine => int32
                }
                """);
        SchemaDocument document = parser.parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver(standardLibrary());

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolver.resolveSchema(document, parser.schemaPositions()));
        assertTrue(thrown.getMessage().contains("pins a floor of zero"), thrown.getMessage());
    }

    /** The default overloads keep throwing, so nothing that exists today changes behaviour. */
    @Test
    void withoutAReceiverTheFirstFailureStillThrows() {
        TsonSchemaParser parser = new TsonSchemaParser(FOUR_BROKEN);
        SchemaDocument document = parser.parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver(standardLibrary());

        assertThrows(TsonSchemaValidationException.class,
                () -> resolver.resolveSchema(document, parser.schemaPositions()));
    }
}
