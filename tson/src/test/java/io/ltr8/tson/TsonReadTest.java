package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonTreeReader;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Tson#treeReader()} / {@link Tson#objectReader()} -- the value-returning counterparts to {@link
 * Tson#validate}: a self-describing document read into a {@link TsonValue} tree (or a bound Java object),
 * schema-validated when it declares a {@code !!schema} and schemaless otherwise, fail-fast (a bad value or
 * a document-selection failure throws {@link TsonReadException}).
 */
class TsonReadTest {

    private static final String POINT_ID = "https://example.test/point-1.tn";
    private static final String POINT_SCHEMA = """
            !!id:"https://example.test/point-1.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            { point => { x: int32  y: int32 } }
            """;

    private static Tson tsonWithPoint() {
        TsonSchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (base.equals(POINT_ID)) {
                return POINT_SCHEMA;
            }
            throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_FOUND,
                    "this fixture serves only " + POINT_ID, null);
        };
        return Tson.builder().schemaSource(source).build();
    }

    private static long asLong(TsonValue node) {
        return node.as(Number.class).orElseThrow().longValue();
    }

    @Test
    void schemaDrivenReadReturnsTheValidatedTree() {
        TsonValue node = tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4 }""");

        assertTrue(node.isRecord());
        assertEquals(Optional.of("point"), node.typeRef());
        assertEquals(3, asLong(node.at("/x")));
        assertEquals(4, asLong(node.at("/y")));
    }

    @Test
    void schemalessReadReturnsATree() {
        TsonValue node = tsonWithPoint().treeReader().read("{ id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09  n: !int32 5 }");

        assertTrue(node.isRecord());
        assertTrue(node.get("id").as(java.util.UUID.class).isPresent());
        assertEquals(5, asLong(node.get("n")));
    }

    @Test
    void aBadValueThrowsFailFast() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 99999999999999 }"""));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, thrown.diagnostic().code());
    }

    @Test
    void aSchemaDrivenDocumentWithNoRootTypeRefThrows() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/point-1.tn"
                { x: 3  y: 4 }"""));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, thrown.diagnostic().code());
        assertTrue(thrown.diagnostic().message().contains("root type-ref"), thrown::getMessage);
    }

    @Test
    void aSchemaTheSourceCannotProvideThrows() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/not-there.tn"
                !point { x: 3  y: 4 }"""));
        assertEquals(Diagnostic.Code.SCHEMA_UNAVAILABLE, thrown.diagnostic().code());
    }

    @Test
    void aRootTypeRefTheSchemaDoesNotDeclareThrows() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/point-1.tn"
                !no_such_type { x: 3  y: 4 }"""));
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, thrown.diagnostic().code());
    }

    // ── readObject: schema-driven bind to a Java object ──

    public record Point(int x, int y) {
    }

    private static Tson tsonWithPointBinding() {
        TsonSchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (base.equals(POINT_ID)) {
                return POINT_SCHEMA;
            }
            throw new IllegalStateException("no schema for " + uri);
        };
        DataNameBinder binder = name -> "point".equals(name) ? Point.class : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext context = TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        return Tson.builder().schemaSource(source).dataBindContext(context).build();
    }

    @Test
    void objectReaderReturnsTheSchemaBoundObject() {
        Point value = tsonWithPointBinding().objectReader().read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4 }""", Point.class);

        assertEquals(new Point(3, 4), value);
    }

    @Test
    void objectReaderWithoutASchemaBindsSchemalessly() {
        // No !!schema -> bind straight into the given class, driven by its descriptor.
        Point value = tsonWithPointBinding().objectReader().read("{ x: 3  y: 4 }", Point.class);

        assertEquals(new Point(3, 4), value);
    }

    @Test
    void objectReaderWithTheWrongClassThrowsBeforeReading() {
        // The schema's root type `point` binds to Point, not String -- caught up front, before any value read.
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPointBinding().objectReader()
                .read("""
                        !!schema:"https://example.test/point-1.tn"
                        !point { x: 3  y: 4 }""", String.class));
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, thrown.diagnostic().code());
    }

    @Test
    void objectReaderValidatesAsItBinds() {
        // y is out of int32 range -- fail-fast, same validation the tree read applies.
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPointBinding().objectReader()
                .read("""
                        !!schema:"https://example.test/point-1.tn"
                        !point { x: 3  y: 99999999999999 }""", Point.class));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, thrown.diagnostic().code());
    }

    @Test
    void readWithoutSchemaBindsEvenWhenTheSchemaIsUnavailable() {
        // read() would SCHEMA_ERROR (the source can't provide this URI); readWithoutSchema binds the class anyway.
        Point value = tsonWithPointBinding().objectReader().readWithoutSchema("""
                !!schema:"https://example.test/not-there.tn"
                !point { x: 3  y: 4 }""", Point.class);

        assertEquals(new Point(3, 4), value);
    }

    @Test
    void aStandaloneObjectReaderIgnoresADeclaredSchema() {
        // Built without a schema environment -> schemaless: any !!schema is ignored, binds to the class
        // (the Jackson-style "target class is the contract" case), even a !!schema the reader couldn't resolve.
        Point value = new TsonObjectReader(tsonWithPointBinding().dataBindContext()).read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4 }""", Point.class);

        assertEquals(new Point(3, 4), value);
    }

    // ── withDiagnostics: the schema-aware collecting read ──

    @Test
    void aCollectingTreeReadValidatesAgainstTheSchemaAndReturnsEveryProblemAtOnce() {
        // Both fields are out of int32 range. Fail-fast stops at the first; collecting reports both,
        // against the schema -- which is the combination no route offered before.
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        TsonValue node = tsonWithPoint().treeReader().withDiagnostics(problems).read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 99999999999999  y: 88888888888888 }""");

        assertEquals(2, problems.diagnostics().size(), problems.diagnostics()::toString);
        assertEquals(Optional.of("/x"), problems.diagnostics().get(0).path());
        assertEquals(Optional.of("/y"), problems.diagnostics().get(1).path());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.diagnostics().get(0).code());
        // The tree still comes back, so a caller has the partial value alongside what was wrong with it.
        assertTrue(node.isRecord());
    }

    @Test
    void aCollectingObjectReadValidatesAgainstTheSchemaAndReturnsEveryProblemAtOnce() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        tsonWithPointBinding().objectReader().withDiagnostics(problems).read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 99999999999999  y: 88888888888888 }""", Point.class);

        assertEquals(2, problems.diagnostics().size(), problems.diagnostics()::toString);
        assertEquals(Optional.of("/x"), problems.diagnostics().get(0).path());
        assertEquals(Optional.of("/y"), problems.diagnostics().get(1).path());
    }

    @Test
    void aDocumentSelectionFailureIsADiagnosticWhenCollectingAndStillThrowsWhenNot() {
        // Reaching the schema at all can fail three ways. Under a collector each is an ordinary
        // diagnostic, so a caller has one shape to render and never catches for a bad document.
        record Case(String source, Diagnostic.Code code) {
        }
        List<Case> cases = List.of(
                new Case("""
                        !!schema:"https://example.test/not-there.tn"
                        !point { x: 3  y: 4 }""", Diagnostic.Code.SCHEMA_UNAVAILABLE),
                new Case("""
                        !!schema:"https://example.test/point-1.tn"
                        { x: 3  y: 4 }""", Diagnostic.Code.VALIDATION_ERROR),
                new Case("""
                        !!schema:"https://example.test/point-1.tn"
                        !no_such_type { x: 3  y: 4 }""", Diagnostic.Code.UNKNOWN_TYPE));

        for (Case testCase : cases) {
            TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
            TsonValue node = tsonWithPoint().treeReader().withDiagnostics(problems).read(testCase.source());

            assertEquals(1, problems.diagnostics().size(), problems.diagnostics()::toString);
            assertEquals(testCase.code(), problems.diagnostics().get(0).code());
            assertNull(node, "no tree is produced when the schema can't be reached");

            // The default reader is untouched by the derived one -- still fail-fast.
            assertThrows(TsonReadException.class, () -> tsonWithPoint().treeReader().read(testCase.source()));
        }
    }

    // ── withSchema/readAs: schema held out of band, data not self-describing ──

    @Test
    void readAsValidatesAgainstTheNamedSchemaAndTypeWithNoDirectiveInTheData() {
        Tson tson = tsonWithPoint();
        tson.resolve(POINT_SCHEMA);

        TsonValue node = tson.treeReader().withSchema(POINT_ID).readAs("{ x: 3  y: 4 }", "point");

        assertTrue(node.isRecord());
        assertEquals(3, asLong(node.at("/x")));

        // Same validation as the self-describing path -- the caller supplied what !!schema + !point would.
        assertThrows(TsonReadException.class,
                () -> tson.treeReader().withSchema(POINT_ID).readAs("{ x: 3  y: 99999999999999 }", "point"));
    }

    @Test
    void readAsBindsToAClassAndComposesWithWithDiagnostics() {
        Tson tson = tsonWithPointBinding();
        tson.resolve(POINT_SCHEMA);

        assertEquals(new Point(3, 4),
                tson.objectReader().withSchema(POINT_ID).readAs("{ x: 3  y: 4 }", "point", Point.class));

        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        tson.objectReader().withSchema(POINT_ID).withDiagnostics(problems)
                .readAs("{ x: 99999999999999  y: 88888888888888 }", "point", Point.class);
        assertEquals(List.of("/x", "/y"), problems.diagnostics().stream().map(d -> d.path().orElseThrow()).toList());
    }

    @Test
    void readAsReportsATypeTheSchemaDoesNotDeclare() {
        Tson tson = tsonWithPoint();
        tson.resolve(POINT_SCHEMA);
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        tson.treeReader().withSchema(POINT_ID).withDiagnostics(problems).readAs("{ x: 3  y: 4 }", "no_such_type");

        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problems.diagnostics().getFirst().code());
    }

    @Test
    void aSchemalessReaderCannotBeGivenASchema() {
        // No schema environment to resolve the URI through -- fail where it's written, not at the read.
        assertThrows(IllegalStateException.class, () -> new TsonTreeReader().withSchema(POINT_ID));
        assertThrows(IllegalStateException.class, () -> new TsonObjectReader().withSchema(POINT_ID));
    }

    /**
     * Trailing content is rejected on every whole-document path. {@code TsonDataStream} is lazy and its root
     * frame only checks when something pulls past the root value, so this pins that each facade entry point
     * still does that pull -- the guarantee is invisible to a reader that simply stops reading.
     */
    @Test
    void everyFacadeEntryPointRejectsContentAfterTheDocumentsValue() {
        Tson tson = tsonWithPoint();
        tson.resolve(POINT_SCHEMA);
        String trailing = "{ x: 3  y: 4 } junk";

        assertThrows(RuntimeException.class, () -> tson.treeReader().read(trailing));
        assertThrows(RuntimeException.class, () -> tson.treeReader().readWithoutSchema(trailing));
        assertThrows(RuntimeException.class, () -> tson.treeReader().withSchema(POINT_ID).readAs(trailing, "point"));
        assertThrows(RuntimeException.class, () -> new TsonTreeReader().read(trailing));
        assertThrows(RuntimeException.class, () -> tsonWithPointBinding().objectReader().read(trailing, Point.class));
    }

    @Test
    void withDiagnosticsReturnsANewReaderAndLeavesTheOriginalFailFast() {
        String bad = """
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 99999999999999 }""";
        var reader = tsonWithPoint().treeReader();
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        reader.withDiagnostics(problems).read(bad);
        assertEquals(1, problems.diagnostics().size());

        assertThrows(TsonReadException.class, () -> reader.read(bad));
    }
}
