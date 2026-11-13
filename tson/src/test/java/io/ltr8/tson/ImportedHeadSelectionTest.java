package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.tree.TsonRecord;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value a schema document reads under an imported head -- a construction's payload, a value entry's, an
 * annotation's value -- selects the types the reading document's closure holds, not only those its head's declaring
 * schema saw ([TSON-SCHEMA] §3.3.4's open {@code subtypes}, §5.2's family open across schemas), and waits on each
 * entry of the document it selects (§3.4.1).
 */
class ImportedHeadSelectionTest {

    private static final String META = "!!import:\"https://tson.io/2026/40/m/meta.tn\"\n";

    private static final String LIB = """
            !!id:"https://example.test/lib.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              handler  => abstract { name: text }
              route    => { kind: text =?  path: text }
              get      => route & { kind?: = "get" }
              api      => { h: handler }
              routed   => { r: route }
              step     => meta.top & { takes: handler }
              slot     => @meta.annotation meta.type_inline
            }
            """;

    private static final String SIBLING = """
            !!id:"https://example.test/sibling.tn"
            !!import:"https://example.test/lib.tn"
            !schema {
              sibling_handler => handler & { more?: text }
              post            => route & { kind?: = "post" }
            }
            """;

    private static String schema(String id, String imports, String declarations) {
        return "!!id:\"https://example.test/" + id + ".tn\"\n" + imports + "!schema {\n" + declarations + "}\n";
    }

    private static Tson tson(String... documents) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("https://example.test/lib.tn", LIB);
        sources.put("https://example.test/sibling.tn", SIBLING);
        for (String document : documents) {
            String id = document.substring(document.indexOf('"') + 1, document.indexOf('"', document.indexOf('"') + 1));
            sources.put(id, document);
        }
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(sources))));
    }

    private static List<Diagnostic> validateSchema(String document) {
        return tson(document).validateSchema(document);
    }

    private static void assertOneProblem(List<Diagnostic> problems, String pointer, String fragment) {
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), problems::toString);
        assertEquals(pointer, problems.getFirst().schemaPointer().orElseThrow(), problems::toString);
        assertTrue(problems.getFirst().message().contains(fragment), problems::toString);
    }

    // ── A construction's payload ──────────────────────────────────────────

    private static final String ROWS = "  rows => meta.array ^ { min_items?: = 1 }\n";

    private static final String RECORD_OF_ROWS =
            "  e => !meta.record { fields: { f => { type: { of: !rows { element_type: text } } } } }\n";

    /** A refined constructor of the document's own, constructed in place in a payload of the kernel's constructor. */
    @Test
    void aPayloadUnderAnImportedHeadConstructsTheDocumentsOwnConstructor() {
        for (String declarations : List.of(ROWS + RECORD_OF_ROWS, RECORD_OF_ROWS + ROWS)) {
            String document = schema("rows", META, declarations);
            Tson tson = tson(document);
            assertEquals(List.of(), tson.validateSchema(document));
            assertEquals(List.of(), tson.validate("!!import:\"https://example.test/rows.tn\"\n!e { f: [a] }"));
            assertEquals(1, tson.validate("!!import:\"https://example.test/rows.tn\"\n!e { f: [] }").size());
        }
    }

    /** Selected two levels down, through a kernel constructor's payload written in place inside another. */
    @Test
    void aPayloadSelectsTheDocumentsOwnConstructorAtAnyDepth() {
        assertEquals(List.of(), validateSchema(schema("deep", META, ROWS + "  e => !meta.record { fields: { f => { type:"
                + " { of: !meta.array { element_type: { of: !rows { element_type: text } } } } } } }\n")));
    }

    /** The document's own constructor's pin holds in the payload: selected, it is read as itself. */
    @Test
    void aPinOnTheDocumentsOwnConstructorStillRefusesAnotherValue() {
        assertOneProblem(validateSchema(schema("pin", META, ROWS
                        + "  e => !meta.record { fields: { f => { type: { of: !rows { element_type: text  min_items: 0 } }"
                        + " } } }\n")),
                "/e", "'min_items' is fixed on 'rows'");
    }

    /** A tag naming an entry of the document that is no type is still refused where a type stands. */
    @Test
    void aTagNamingNoTypeIsStillRefused() {
        assertOneProblem(validateSchema(schema("notype", META, "  e2 => { element_type: text }\n"
                        + "  e => !meta.record { fields: { f => { type: { of: !e2 { element_type: text } } } } }\n")),
                "/e", "'e2' is not admissible at a 'meta.type' position");
    }

    /** An imported constructor whose instances no class binds: its payload is read in tree mode, selecting alike. */
    @Test
    void aTreeReadPayloadSelectsTheDocumentsOwnSubtype() {
        String document = schema("steps", "!!import:\"https://example.test/lib.tn\"\n",
                "  s => !step { takes: !my_handler { name: x } }\n  my_handler => handler & { extra?: text }\n");
        Tson tson = tson(document);
        assertEquals(List.of(), tson.validateSchema(document));
        ValueEntry s = assertInstanceOf(ValueEntry.class,
                tson.treeRegistry().core().resolveLinked("https://example.test/steps.tn").schema().entries().get("s"));
        assertInstanceOf(TsonRecord.class, s.value());
    }

    // ── A value entry's payload ───────────────────────────────────────────

    /** A value of an imported type, holding a value tagged with the document's own subtype of an imported base. */
    @Test
    void aValueEntrySelectsTheDocumentsOwnSubtypeByTag() {
        String document = schema("orders", "!!import:\"https://example.test/lib.tn\"\n",
                "  orders => !api { h: !my_handler { name: x } }\n  my_handler => handler & { extra?: text }\n");
        assertEquals(List.of(), validateSchema(document));
    }

    /** No tag written: the document's own member of an imported family is selected by its pin (§5.2). */
    @Test
    void aValueEntrySelectsTheDocumentsOwnFamilyMemberByItsPin() {
        String document = schema("routes", "!!import:\"https://example.test/lib.tn\"\n",
                "  orders => !routed { r: { kind: put  path: \"/\" } }\n  put => route & { kind?: = \"put\" }\n");
        assertEquals(List.of(), validateSchema(document));

        assertOneProblem(validateSchema(schema("unpinned", "!!import:\"https://example.test/lib.tn\"\n",
                        "  orders => !routed { r: { kind: patch  path: \"/\" } }\n  put => route & { kind?: = \"put\" }\n")),
                "/orders", "no member of 'route' pins kind to patch");
    }

    /** A subtype and a member a sibling import declares are beyond the head's declaring schema too. */
    @Test
    void aValueEntrySelectsASiblingImportsSubtypeAndMember() {
        for (String imports : List.of(
                "!!import:\"https://example.test/lib.tn\"\n!!import:\"https://example.test/sibling.tn\"\n",
                "!!import:\"https://example.test/sibling.tn\"\n!!import:\"https://example.test/lib.tn\"\n")) {
            assertEquals(List.of(), validateSchema(schema("siblings", imports,
                    "  orders => !api { h: !sibling_handler { name: x } }\n"
                            + "  posted => !routed { r: { kind: post  path: \"/\" } }\n")));
        }
    }

    // ── An annotation's value ─────────────────────────────────────────────

    /** An annotation whose type is imported, its value constructing the document's own constructor. */
    @Test
    void anAnnotationValueUnderAnImportedTypeSelectsTheDocumentsOwnConstructor() {
        assertEquals(List.of(), validateSchema(schema("slots", "!!import:\"https://example.test/lib.tn\"\n",
                ROWS + "  r => @slot:{ of: !rows { element_type: text } } { a: text }\n")));
        assertEquals(List.of(), validateSchema(schema("inline", META,
                "  r => @meta.type_inline:{ of: !rows { element_type: text } } { a: text }\n" + ROWS)));
    }

    /**
     * Read while an entry that reads no value is being made ready, before the constructor it selects: the annotation
     * waits on it.
     */
    @Test
    void anAnnotationValueWaitsOnTheConstructorItSelects() {
        assertEquals(List.of(), validateSchema(schema("early", META,
                "  al => @meta.type_inline:{ of: !rows { element_type: text } } text\n" + ROWS)));
    }

    // ── Cycles ────────────────────────────────────────────────────────────

    /** An entry whose own annotation selects it waits on itself. */
    @Test
    void aValueSelectingTheEntryItIsReadForIsAWaitCycle() {
        assertOneProblem(validateSchema(schema("self", META,
                        "  rows => @meta.type_inline:{ of: !rows { element_type: text } }\n"
                                + "    meta.array ^ { min_items?: = 1 }\n")),
                "/rows", "'rows' waits on 'rows'");
        assertOneProblem(validateSchema(schema("selfrecord", META,
                        "  e => !meta.record { fields: { f => { type: { of: !e { fields: {} } } } } }\n")),
                "/e", "'e' waits on 'e'");
    }

    /** Two entries each selecting the other: the cycle is named in wait order. */
    @Test
    void twoValuesSelectingEachOthersEntryAreAWaitCycle() {
        List<Diagnostic> problems = validateSchema(schema("pair", META,
                "  a => @meta.type_inline:{ of: !b { element_type: text } } meta.array ^ { min_items?: = 1 }\n"
                        + "  b => @meta.type_inline:{ of: !a { element_type: text } } meta.array ^ { min_items?: = 1 }\n"));
        assertTrue(problems.stream().anyMatch(problem -> problem.message().startsWith("'a' waits on 'b' waits on 'a'")),
                problems::toString);
    }
}
