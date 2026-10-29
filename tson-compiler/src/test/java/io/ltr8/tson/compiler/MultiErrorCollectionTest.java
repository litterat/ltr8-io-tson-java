package io.ltr8.tson.compiler;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The actual payoff of {@link TsonReadContext}: a single data file with three independent, unrelated
 * problems (a missing required field, an out-of-range sibling field, and a bad element nested inside
 * an array field) reads through a collecting context in one call and reports all three, each with
 * its own correct {@link Diagnostic#path()} and {@link Diagnostic#dataPosition()} -- not just the
 * first one found, and not just that a single problem could be found in isolation.
 */
class MultiErrorCollectionTest {

    private static int lineOf(String source, String needle) {
        int index = source.indexOf(needle);
        assertTrue(index >= 0, "expected to find '" + needle + "' in the source text");
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        TsonCompiledMetaRegistry core = new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext());
        return TsonCompiledSchemaRegistry.tree(core).compile(linkedSchema);
    }

    @Test
    void threeIndependentProblemsInOneFileAllSurfaceInOnePass() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("integer", new IntegerType(new IntegerSize(8, true)));
        entries.put("numbers", ArrayBody.of(TypeRef.of("integer")));
        entries.put("my_record", RecordBody.of(RecordBody.fieldMap(
                Map.entry("value", RecordField.required(TypeRef.of("integer"))),
                Map.entry("tag", RecordField.required(TypeRef.of("integer"))),
                Map.entry("items", RecordField.required(TypeRef.of("numbers"))))));
        TsonSchema schema = new TsonSchema("https://example.test/multi-error.tn",
                List.of(), entries);
        TsonCompiledSchema compiled = compile(new TsonLinkedSchema(schema));

        String dataSource = """
                {
                  tag: 200
                  items: [1 hello 3]
                }
                """;
        DiagnosticsCollector problems = new DiagnosticsCollector();

        Object result = compiled.get("my_record").read(TestDocuments.document(dataSource, problems));

        assertEquals(3, problems.diagnostics().size(), problems.diagnostics().toString());

        Map<String, Diagnostic> byPath = new LinkedHashMap<>();
        for (Diagnostic diagnostic : problems.diagnostics()) {
            byPath.put(diagnostic.path().orElseThrow(), diagnostic);
        }
        assertEquals(Set.of("/value", "/tag", "/items/1"), byPath.keySet());

        Diagnostic missingValue = byPath.get("/value");
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, missingValue.code());
        assertEquals(lineOf(dataSource, "{"), missingValue.dataPosition().orElseThrow().line());

        Diagnostic outOfRangeTag = byPath.get("/tag");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, outOfRangeTag.code());
        assertEquals(lineOf(dataSource, "200"), outOfRangeTag.dataPosition().orElseThrow().line());

        Diagnostic badElement = byPath.get("/items/1");
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, badElement.code());
        assertEquals(lineOf(dataSource, "hello"), badElement.dataPosition().orElseThrow().line());

        // Collecting mode kept reading past every failure, which is how all three surfaced; the value itself is
        // all-or-nothing, so the record reads to nothing and the diagnostics are the answer.
        assertNull(result);
    }
}
