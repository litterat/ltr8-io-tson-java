package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.reader.Dom;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), new IntegerType(new IntegerSize(8, true))));
        entries.put("numbers", TypeDefinition.product(ArrayBody.of(TypeRef.of("integer"))));
        entries.put("my_record", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("value", TypeRef.of("integer")),
                RecordField.required("tag", TypeRef.of("integer")),
                RecordField.required("items", TypeRef.of("numbers"))))));
        TsonSchema schema = new TsonSchema("https://example.test/multi-error.tn",
                "https://example.test/meta.tn", List.of(), entries);
        TsonCompiledSchema compiled = compile(new TsonLinkedSchema(schema));

        String dataSource = """
                {
                  tag: 200
                  items: [1 hello 3]
                }
                """;
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Dom.of((io.ltr8.tson.tree.TsonNode)
                compiled.get("my_record").read(TestDocuments.document(dataSource, problems)));

        assertEquals(3, problems.diagnostics().size(), problems.diagnostics().toString());

        Map<String, Diagnostic> byPath = new LinkedHashMap<>();
        for (Diagnostic diagnostic : problems.diagnostics()) {
            byPath.put(diagnostic.path(), diagnostic);
        }
        assertEquals(Set.of("/value", "/tag", "/items/1"), byPath.keySet());

        Diagnostic missingValue = byPath.get("/value");
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, missingValue.code());
        assertEquals(lineOf(dataSource, "{"), missingValue.dataPosition().orElseThrow().line());

        Diagnostic outOfRangeTag = byPath.get("/tag");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, outOfRangeTag.code());
        assertEquals(lineOf(dataSource, "200"), outOfRangeTag.dataPosition().orElseThrow().line());

        Diagnostic badElement = byPath.get("/items/1");
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, badElement.code());
        assertEquals(lineOf(dataSource, "hello"), badElement.dataPosition().orElseThrow().line());

        // Collecting mode kept reading despite every failure -- the record itself still comes back as
        // a real Map (DOM mode tolerates null values fine), "value"/"tag" are null placeholders, and
        // "items" keeps its own null placeholder at the one bad index rather than the whole field
        // being dropped or the whole record read aborting.
        assertNull(result.get("value"));
        assertNull(result.get("tag"));
        List<?> items = (List<?>) result.get("items");
        assertEquals((byte) 1, items.get(0));
        assertNull(items.get(1));
        assertEquals((byte) 3, items.get(2));
    }
}
