package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonCompiledSchemaRegistry;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A record field named twice is a validation error ({@code DUPLICATE_FIELD}) -- [TSON-DATA] §2.5's
 * SHOULD-warn taken as an error, per {@code SPEC-FEEDBACK.md} #41/#42 -- and the "last value wins"
 * recovery still runs underneath it, because {@link RecordAbstractReader} reads forward in one pass and
 * has no way to skip an occurrence it cannot yet know is shadowed.
 *
 * <p>That is also what settles {@code SPEC-FEEDBACK.md} #21: every occurrence is decoded, so a shadowed
 * one's own problems are reported alongside the duplication itself rather than going unvalidated.
 */
class DuplicateFieldTest {

    private static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        TsonCompiledMetaRegistry core = new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext());
        return TsonCompiledSchemaRegistry.tree(core).compile(linkedSchema);
    }

    @Test
    void aMalformedFirstOccurrenceStillReportsEvenThoughTheValidSecondOccurrenceWins() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("int8", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), new IntegerType(new IntegerSize(8, true))));
        entries.put("holder", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("value", TypeRef.of("int8"))))));
        TsonSchema schema = new TsonSchema("https://example.test/dup-field.tn",
                "https://example.test/meta.tn", List.of(), entries);
        TsonCompiledSchema compiled = compile(new TsonLinkedSchema(schema));

        // "value" appears twice: first as 999 (out of int8's own -128..127 range), then as 42 (valid).
        String dataSource = "{ value: 999  value: 42 }";
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Dom.of((TsonValue)
                compiled.get("holder").read(TestDocuments.document(dataSource, problems)));

        // Two independent problems, in stream order: the malformed first occurrence was genuinely
        // read and validated (out-of-range 999), and the second occurrence is the duplication itself.
        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, Diagnostic.Code.DUPLICATE_FIELD),
                problems.diagnostics().stream().map(Diagnostic::code).toList(),
                problems.diagnostics().toString());

        // The value still follows §2.5's "last value wins" -- the document is invalid either way, and
        // reporting is not a reason to hand back a record missing a field the data did state. DOM mode
        // narrows a real int8-typed atom to a Java byte (AtomTypeReader.INTEGER_TYPE, unrelated here).
        assertEquals((byte) 42, result.get("value"));
    }

    /**
     * A name stated three times is two repeats, each reported at its own position -- the continuation policy
     * this reader stack applies everywhere, so one collecting pass finds every one rather than the first.
     */
    @Test
    void everyRepeatIsReported() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("int8", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), new IntegerType(new IntegerSize(8, true))));
        entries.put("holder", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("value", TypeRef.of("int8"))))));
        TsonCompiledSchema compiled = compile(new TsonLinkedSchema(new TsonSchema(
                "https://example.test/dup-field.tn", "https://example.test/meta.tn", List.of(), entries)));
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        compiled.get("holder").read(TestDocuments.document("{ value: 1  value: 2  value: 3 }", problems));

        assertEquals(List.of(Diagnostic.Code.DUPLICATE_FIELD, Diagnostic.Code.DUPLICATE_FIELD),
                problems.diagnostics().stream().map(Diagnostic::code).toList(),
                problems.diagnostics().toString());
        assertEquals("/value", problems.diagnostics().getFirst().path());
    }
}
