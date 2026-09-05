package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLoader;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-error linking. The case this exists for is the one {@code BACKLOG.md} used as its baseline for how
 * poor schema-side reporting was: a schema with two unresolved references used to report one, so an author
 * fixed a reference, reran, and met the next.
 *
 * <p>Linking has no placeholder machinery, unlike resolution -- validation produces no value, so a failing
 * entry is simply reported and the loop moves to the next one.
 */
class TsonSchemaLinkerDiagnosticsTest {

    private static final String ID = "https://example.test/link-diagnostics.tn";

    /** A position carrying real line/column, so a diagnostic has something to point at. */
    private record Pos(int line, int column, int byteOffset) implements SourcePosition {
    }

    private static TypeDefinition record(int line, RecordField... fields) {
        return new TypeDefinition(Optional.empty(), io.ltr8.tson.schema.meta.TypeKind.PRODUCT, 
                List.of(), List.of(), Optional.empty(), RecordBody.of(List.of(fields)),
                Optional.of(new Pos(line, 3, line * 10)), io.ltr8.annotation.Annotations.empty());
    }

    /** Three entries, each with a field naming a type nothing declares -- three independent failures. */
    private static TsonSchema threeUnresolvedReferences() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("a", record(4, RecordField.required("x", TypeRef.of("no_such_type"))));
        entries.put("b", record(5, RecordField.required("y", TypeRef.of("also_missing"))));
        entries.put("c", record(6, RecordField.required("z", TypeRef.of("missing_too"))));
        entries.put("fine", record(7));
        return new TsonSchema(ID, "https://tson.io/2026/35/m/meta.tn", List.of(), entries);
    }

    private static final TsonSchemaLoader NO_IMPORTS = uri -> Optional.empty();

    private static List<Diagnostic> linkCollecting(TsonSchema schema) {
        TsonDiagnosticsCollector collector = new TsonDiagnosticsCollector();
        TsonLinkedSchema linked = TsonSchemaLinker.link(schema, NO_IMPORTS, collector);
        // Linking still returns a result -- it is just not a proof that linking succeeded, which is what the
        // receiver's own report count tells the caller.
        assertTrue(linked.schema().entries().containsKey("fine"));
        return collector.diagnostics();
    }

    @Test
    void aSchemaWithThreeUnresolvedReferencesReportsThree() {
        List<Diagnostic> diagnostics = linkCollecting(threeUnresolvedReferences());

        assertEquals(3, diagnostics.size(), () -> "expected one per broken entry, got " + diagnostics);
        assertEquals(List.of("/a", "/b", "/c"),
                diagnostics.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
    }

    @Test
    void eachDiagnosticCarriesTheSchemaIdentityAndTheEntrysOwnPosition() {
        for (Diagnostic diagnostic : linkCollecting(threeUnresolvedReferences())) {
            assertEquals("example.test/link-diagnostics.tn", diagnostic.schemaId());
            assertEquals(Diagnostic.Code.SCHEMA_ERROR, diagnostic.code());
            assertTrue(diagnostic.schemaPosition().isPresent(),
                    () -> "no position on " + diagnostic.schemaPointer().orElseThrow());
        }
    }

    /** A schema problem, so the data end stays empty -- nothing has been read against this schema. */
    @Test
    void aLinkDiagnosticCarriesNoDataLocation() {
        for (Diagnostic diagnostic : linkCollecting(threeUnresolvedReferences())) {
            assertEquals(Optional.empty(), diagnostic.path());
            assertTrue(diagnostic.dataPosition().isEmpty());
        }
    }

    /** An unresolved *supertype* is a separate check from an unresolved field type; both are collected. */
    @Test
    void unresolvedSupertypesAndFieldTypesAreBothCollected() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("bad_field", record(4, RecordField.required("x", TypeRef.of("no_such_type"))));
        entries.put("bad_supertype", new TypeDefinition(Optional.empty(),
                io.ltr8.tson.schema.meta.TypeKind.PRODUCT,  List.of("no_such_parent"),
                List.of(), Optional.empty(), RecordBody.of(List.of()), Optional.of(new Pos(5, 3, 50)),
                io.ltr8.annotation.Annotations.empty()));
        entries.put("fine", record(6));

        List<Diagnostic> diagnostics = linkCollecting(
                new TsonSchema(ID, "https://tson.io/2026/35/m/meta.tn", List.of(), entries));

        assertEquals(List.of("/bad_field", "/bad_supertype"),
                diagnostics.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
    }

    /** The existing two-argument overload is untouched: first failure, original exception type. */
    @Test
    void withoutAReceiverTheFirstFailureStillThrows() {
        TsonSchema schema = threeUnresolvedReferences();

        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(schema, NO_IMPORTS));
    }
}
