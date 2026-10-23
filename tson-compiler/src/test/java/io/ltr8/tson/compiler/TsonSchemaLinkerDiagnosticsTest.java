package io.ltr8.tson.compiler;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLoader;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Type;
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

    private static Top record() {
        return RecordBody.of(RecordBody.fieldMap());
    }

    private static Top record(Map.Entry<String, RecordField> field) {
        return RecordBody.of(RecordBody.fieldMap(field));
    }

    /** Where an entry was declared: line {@code line}, as the facts a resolved schema records. */
    private static TsonSchema.EntryFacts declaredAt(int line) {
        return TsonSchema.EntryFacts.at(Optional.of(new Pos(line, 3, line * 10)));
    }

    /** Three entries, each with a field naming a type nothing declares -- three independent failures. */
    private static TsonSchema threeUnresolvedReferences() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("a", record(Map.entry("x", RecordField.required(TypeRef.of("no_such_type")))));
        entries.put("b", record(Map.entry("y", RecordField.required(TypeRef.of("also_missing")))));
        entries.put("c", record(Map.entry("z", RecordField.required(TypeRef.of("missing_too")))));
        entries.put("fine", record());
        return new TsonSchema(ID, "https://tson.io/2026/38/m/meta.tn", List.of(), AnnotatedMap.of(entries), false,
                Map.of(), Map.of("a", declaredAt(4), "b", declaredAt(5), "c", declaredAt(6), "fine", declaredAt(7)));
    }

    private static final TsonSchemaLoader NO_IMPORTS = uri -> Optional.empty();

    private static List<Diagnostic> linkCollecting(TsonSchema schema) {
        DiagnosticsCollector collector = new DiagnosticsCollector();
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
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("bad_field", record(Map.entry("x", RecordField.required(TypeRef.of("no_such_type")))));
        entries.put("bad_supertype",
                ((Type) (RecordBody.of(RecordBody.fieldMap()))).withSupertypes(List.of("no_such_parent")));
        entries.put("fine", record());

        List<Diagnostic> diagnostics = linkCollecting(
                new TsonSchema(ID, "https://tson.io/2026/38/m/meta.tn", List.of(), entries));

        assertEquals(List.of("/bad_field", "/bad_supertype"),
                diagnostics.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
    }

    /** The existing two-argument overload is untouched: first failure, original exception type. */
    @Test
    void withoutAReceiverTheFirstFailureStillThrows() {
        TsonSchema schema = threeUnresolvedReferences();

        assertThrows(SchemaValidationException.class, () -> TsonSchemaLinker.link(schema, NO_IMPORTS));
    }
}
