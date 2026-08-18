package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TextType;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §7.2: "Records are closed under their type. When a schema is in scope and a record's type
 * is known, the record MUST contain only fields defined by its type; fields not present in the type
 * definition are validation errors." Both positions the rule names are covered here -- a directly-typed
 * record and a structurally-positioned one (a record at a record-typed field) -- in both read modes, since
 * {@link RecordAbstractReader#readFields} is the single site and the two subclasses inherit it.
 *
 * <p>The rule's exemption ("Schemaless records have no closure rule") needs no flag: a schemaless read goes
 * through {@code SchemalessObjectReader}/{@code SchemalessTreeReader}, which never reach this class.
 * {@code SchemalessValidationTest} pins that from the other side.
 */
class RecordClosureTest {

    private static TypeDefinition atom(io.ltr8.tson.schema.meta.Top body) {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(), List.of(),
                Optional.empty(), body);
    }

    /** {@code person => { name: text  address: addr }}, {@code addr => { city: text }} -- a record inside a record. */
    private static TsonCompiledSchema personSchema() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("text", atom(TextType.UNCONSTRAINED));
        entries.put("integer", atom(IntegerType.UNCONSTRAINED));
        entries.put("addr", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("city", TypeRef.of("text"))))));
        entries.put("person", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("name", TypeRef.of("text")),
                RecordField.required("address", TypeRef.of("addr"))))));
        TsonSchema schema = new TsonSchema("https://example.test/closure.tn",
                "https://example.test/meta.tn", List.of(), entries);
        return TsonSchemaCompiler.compile(new TsonLinkedSchema(schema), ValueReaderFactoryRegistry.tree());
    }

    @Test
    void anUnknownFieldIsReportedAtEveryDepthInOnePass() {
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();
        String source = """
                {
                  name: "a"
                  address: { city: "x"  nested_bogus: 1 }
                  top_bogus: 2
                }
                """;

        TsonValue person = (TsonValue) personSchema().get("person")
                .read(TestDocuments.document(source, problems));

        Map<String, Diagnostic> byPath = new LinkedHashMap<>();
        for (Diagnostic diagnostic : problems.diagnostics()) {
            byPath.put(diagnostic.path().orElseThrow(), diagnostic);
        }
        assertEquals(List.of("/address/nested_bogus", "/top_bogus"), byPath.keySet().stream().sorted().toList());
        for (Diagnostic diagnostic : problems.diagnostics()) {
            assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, diagnostic.code());
            // §8.1's MUST -- the position of the offending name in the data, not of the record around it.
            assertTrue(diagnostic.dataPosition().isPresent(), diagnostic::toString);
        }
        assertEquals(3, byPath.get("/address/nested_bogus").dataPosition().orElseThrow().line());
        assertEquals(4, byPath.get("/top_bogus").dataPosition().orElseThrow().line());

        // Continuation policy: the value still comes back whole, with every field the schema *does* declare.
        assertEquals("a", person.get("name").asString().orElseThrow());
        assertEquals("x", person.get("address").get("city").asString().orElseThrow());
    }

    /**
     * The diagnostic has to answer "then what may I write?", because that is what turns a retry loop into a
     * one-shot correction -- the whole point for the schema-authoring case, where the unknown member is a
     * JSON Schema facet name and the real vocabulary is a lookup the author cannot do from the error alone.
     * Schema order, not {@code fieldIndex}'s hash order, so the same schema always says the same thing.
     */
    @Test
    void theDiagnosticNamesTheTypesRealVocabularyInSchemaOrder() {
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        personSchema().get("person").read(TestDocuments.document("{ nope: 1 }", problems));

        Diagnostic diagnostic = problems.diagnostics().getFirst();
        assertEquals("name | address", diagnostic.expected());
        assertEquals("nope", diagnostic.actual());
        assertTrue(diagnostic.message().contains("unknown field 'nope' on 'person'"), diagnostic.message());
        assertTrue(diagnostic.message().contains("(name | address)"), diagnostic.message());
    }

    /** Fail-fast is the default receiver, so an unknown field stops the read like any other violation. */
    @Test
    void failFastThrowsOnTheFirstUnknownField() {
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> personSchema().get("person").read(TestDocuments.document("{ name: \"a\" nope: 1 }")));

        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, thrown.diagnostic().code());
    }

    /**
     * Object-binding mode enforces the same rule from the same code, against the schema's field list rather
     * than the target class's -- {@code text_type} has no {@code minLength} member, and the bound
     * {@link TextType} would have ignored one regardless of what the schema said.
     *
     * <p>Collecting mode binds the record to {@code null} rather than to a {@link TextType} carrying the
     * fields that were fine. That is {@code ConstructionGuard}'s all-or-nothing rule and not specific to
     * closure: <em>any</em> diagnostic raised while a value is being read -- its own field's or a
     * descendant's, and whether or not it left an argument unfilled -- stops it being assembled. A stray
     * field says the document is wrong, which is the only question the rule asks. Tree mode keeps the value,
     * which is why the deeper assertions above are written against it.
     */
    @Test
    void closureReachesObjectBindingModeToo() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", atom(IntegerType.UNCONSTRAINED));
        entries.put("text", atom(TextType.UNCONSTRAINED));
        entries.put("text_type", TypeDefinition.product(RecordBody.of(List.of(
                new RecordField("min_length", TypeRef.of("integer"), FieldState.OPTIONAL, Optional.empty(),
                        Optional.empty()),
                new RecordField("max_length", TypeRef.of("integer"), FieldState.OPTIONAL, Optional.empty(),
                        Optional.empty()),
                new RecordField("length", TypeRef.of("integer"), FieldState.OPTIONAL, Optional.empty(),
                        Optional.empty()),
                new RecordField("pattern", TypeRef.of("text"), FieldState.OPTIONAL, Optional.empty(),
                        Optional.empty())))));
        TsonSchema schema = new TsonSchema("https://example.test/bind-closure.tn",
                "https://example.test/meta.tn", List.of(), entries);
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(new TsonLinkedSchema(schema),
                ValueReaderFactoryRegistry.bind(context));
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        Object bound = compiled.get("text_type")
                .read(TestDocuments.document("{ min_length: 1  minLength: 2 }", problems));

        Diagnostic diagnostic = problems.diagnostics().getFirst();
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, diagnostic.code());
        assertEquals(Optional.of("/minLength"), diagnostic.path());
        assertEquals("min_length | max_length | length | pattern", diagnostic.expected());
        assertNull(bound);

        // The same schema, the same reader, nothing unknown: the binding itself still works.
        assertEquals(Optional.of(1), assertInstanceOf(TextType.class,
                compiled.get("text_type").read(TestDocuments.document("{ min_length: 1 }"))).minLength());
    }
}
