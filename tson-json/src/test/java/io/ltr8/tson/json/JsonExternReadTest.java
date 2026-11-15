package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.json.tree.JsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JSON value read through an extern ([TSON-JSON] §8.6, [TSON-SCHEMA] §7.9): the {@code $type} leading the object, or
 * its wrapper, in the scope that holds it, and every other name in the value in the foreign schema's, at every
 * position an extern types. Each refusal is the text encoding's, by code and data path.
 */
class JsonExternReadTest {

    static final String CORE = "https://tson.io/2026/40/m/core.tn";
    static final String ORDER_1 = "https://example.test/order-1.tn";
    static final String ORDER_2 = "https://example.test/order-2.tn";
    static final String LIB = "https://example.test/lib.tn";
    static final String SHIPPING = "https://example.test/shipping.tn";
    static final String THIRD = "https://example.test/third.tn";
    static final String MID = "https://example.test/mid.tn";
    static final String OUTER = "https://example.test/outer.tn";
    static final String EXPRESS = "https://example.test/express.tn";
    static final String IMPORTER = "https://example.test/importer.tn";

    /** Schema documents served by identity: two schemas that both declare {@code order}, and what reads through them. */
    static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    static {
        schema(ORDER_1, """
                order => { id: text  payment: card }
                card  => { number: text }
                """);
        schema(ORDER_2, """
                order => { ref: text  lines: [line]  payment: card }
                rush  => order & { by: text }
                line  => { sku: text  qty: int32 }
                card  => { pan: text }
                """);
        schema(LIB, """
                rec       => { a: text  memo?: memo }
                memo      => { note: text }
                pet       => abstract { name: text }
                dog       => pet & { barks: boolean }
                shape     => abstract { kind: text =?  size: int32 }
                circle    => shape & { kind?: = "circle" }
                count     => !integer ^ { min: 0 }
                colour    => !enum [red green]
                either    => (text | int32)
                names     => [text]
                lookup    => { text => int32 }
                pair      => [text, int32]
                rec_alias => rec
                holder    => { any: meta.declared }
                named     => { r: meta.ref<meta.record> }
                label     => text
                contact   => (email | uri)
                ext_card  => !meta.extern { schema: "https://example.test/order-1.tn"  type: card }
                """, ORDER_1);
        schema(SHIPPING, extern("order_v1", ORDER_1, "order") + extern("order_v2", ORDER_2, "order")
                + extern("rush_v2", ORDER_2, "rush") + extern("rec_x", LIB, "rec") + extern("pet_x", LIB, "pet")
                + extern("dog_x", LIB, "dog") + extern("shape_x", LIB, "shape")
                + extern("circle_x", LIB, "circle") + extern("count_x", LIB, "count")
                + extern("colour_x", LIB, "colour") + extern("either_x", LIB, "either")
                + extern("names_x", LIB, "names") + extern("lookup_x", LIB, "lookup")
                + extern("pair_x", LIB, "pair") + extern("alias_x", LIB, "rec_alias")
                + extern("holder_x", LIB, "holder") + extern("named_x", LIB, "named")
                + extern("card_x", LIB, "ext_card") + extern("contact_x", LIB, "contact")
                + extern("email_x", LIB, "email") + """
                oa       => order_v1
                memo     => { other: int32 }
                page     => <T> { items: [T] }
                paged    => page<rec_x>
                envelope => { id: text  body: (order_v1 | order_v2) }
                single   => { o: order_v2 }
                rushed   => { o: rush_v2 }
                all      => {
                  r?: rec_x  p?: pet_x  s?: shape_x  c?: count_x  col?: colour_x  e?: either_x  n?: names_x
                  l?: lookup_x  t?: pair_x  a?: alias_x  k?: card_x  h?: holder_x  nm?: named_x  oa?: oa
                  els?: [rec_x]  m?: { text => rec_x }  keys?: { count_x => text }  tup?: [rec_x, text]
                  dis?: (rec_x | int32)  any?: meta.declared  pg?: page<rec_x>  pd?: paged  ct?: contact_x
                }
                """);
        schema(THIRD, "thing => { t: text }\nmemo => { third: text }\n");
        schema(MID, extern("thing_x", THIRD, "thing") + "holder => { th: thing_x  any?: meta.declared }\n"
                + "memo => { mid: text }\n");
        schema(OUTER, extern("holder_x", MID, "holder") + "outer => { h: holder_x }\nmemo => { outer: text }\n");
        schema(EXPRESS, "express => order & { at: text }\n", ORDER_2);
        schema(IMPORTER, extern("o2", ORDER_2, "order") + "holder => { o: o2 }\n", EXPRESS);
    }

    static void schema(String id, String body, String... imports) {
        StringBuilder text = new StringBuilder("!!id:\"" + id + "\"\n!!import:\"" + CORE + "\"\n");
        for (String each : imports) {
            text.append("!!import:\"").append(each).append("\"\n");
        }
        DOCUMENTS.put(id, text.append("!schema {\n").append(body.indent(2)).append("}\n").toString());
    }

    static String extern(String entry, String schema, String type) {
        return entry + " => !meta.extern { schema: \"" + schema + "\"  type: " + type + " }\n";
    }

    static ProcessorConfig config() {
        SchemaSource source = uri -> DOCUMENTS.entrySet().stream()
                .filter(document -> CanonicalIdentity.sameIdentity(uri, document.getKey()))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND,
                        "no document is served under '" + uri + "'", null));
        return ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source));
    }

    static final Tson TSON = Tson.of(config());
    static final Json JSON = Json.of(config()).withSchemas(TSON.schemaRegistry());

    static {
        // A JSON read names a schema the registry already holds (§3.4's out-of-band route).
        DOCUMENTS.values().forEach(TSON::resolve);
    }

    private record Read(JsonValue value, List<Diagnostic> problems) {
    }

    private static Read read(String schema, String root, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        JsonValue value = JSON.treeReader().withSchema(schema).withDiagnostics(problems::add).readAs(json, root);
        return new Read(value, problems);
    }

    private static List<String> shown(List<Diagnostic> problems) {
        return problems.stream().map(d -> d.code() + " " + d.path().orElse("") + ": " + d.message()).toList();
    }

    /** {@code json} reads clean, writes back, and the written document reads to the same tree. */
    private static void assertReadsBack(String schema, String root, String json) {
        Read first = read(schema, root, json);
        assertEquals(List.of(), shown(first.problems()), json);
        String written = JSON.treeWriter().toJson(first.value());
        Read again = read(schema, root, written);
        assertEquals(List.of(), shown(again.problems()), written);
        assertEquals(first.value(), again.value(), written);
    }

    /** The text document's diagnostics, as code and data path, for the parity of a refusal. */
    private static List<String> textRefusals(String schema, String text) {
        List<Diagnostic> problems = new ArrayList<>();
        TSON.treeReader().withDiagnostics(problems::add).read("!!import:\"" + schema + "\"\n" + text);
        return problems.stream().map(d -> d.code() + " " + d.path().orElse("")).toList();
    }

    private static List<String> refusals(List<Diagnostic> problems) {
        return problems.stream().map(d -> d.code() + " " + d.path().orElse("")).toList();
    }

    /** {@code order_v2} is the document's name for the extern, and {@code card} the foreign schema's. */
    @Test
    void theSection86ExampleReadsTheBoundaryInTheEnclosingScopeAndTheBodyInTheForeignOne() {
        Read read = read(SHIPPING, "envelope", """
                {"id": "a", "body": {"$type": "order_v2", "ref": "r", "lines": [{"sku": "A", "qty": 1}],
                 "payment": {"$type": "card", "pan": "4111"}}}""");
        assertEquals(List.of(), shown(read.problems()));
        assertEquals("{\"id\":\"a\",\"body\":{\"ref\":\"r\",\"lines\":[{\"sku\":\"A\",\"qty\":1}],"
                + "\"payment\":{\"pan\":\"4111\"}}}", JSON.treeWriter().toJson(read.value()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "single|{\"o\": {\"ref\": \"r\", \"lines\": [{\"$type\": \"line\", \"sku\": \"s\", \"qty\": 1}], "
                    + "\"payment\": {\"pan\": \"p\"}}}",
            "single|{\"o\": {\"$type\": \"order_v2\", \"ref\": \"r\", \"lines\": [], \"payment\": {\"pan\": \"p\"}}}",
            "single|{\"o\": {\"$type\": \"order_v2\", \"$value\": {\"ref\": \"r\", \"lines\": [], "
                    + "\"payment\": {\"pan\": \"p\"}}}}",
            "all|{\"r\": {\"a\": \"x\", \"memo\": {\"$type\": \"memo\", \"note\": \"n\"}}}",
            "all|{\"s\": {\"kind\": \"circle\", \"size\": 3}}",
            "all|{\"s\": {\"$type\": \"circle_x\", \"kind\": \"circle\", \"size\": 3}}",
            "all|{\"s\": {\"$type\": \"shape_x\", \"kind\": \"circle\", \"size\": 3}}",
            "all|{\"c\": 3}",
            "all|{\"c\": {\"$type\": \"count_x\", \"$value\": 3}}",
            "all|{\"col\": \"green\"}",
            "all|{\"e\": 4}",
            "all|{\"e\": {\"$type\": \"either_x\", \"$value\": 4}}",
            "all|{\"e\": {\"$type\": \"either_x\", \"$value\": {\"$type\": \"int32\", \"$value\": 4}}}",
            "all|{\"e\": {\"$type\": \"int32\", \"$value\": 4}}",
            "all|{\"n\": [\"a\", \"b\"]}",
            "all|{\"l\": {\"x\": 1}}",
            "all|{\"l\": {\"$type\": 1, \"$value\": 2}}",
            "all|{\"t\": [\"a\", 1]}",
            "all|{\"a\": {\"a\": \"x\"}}",
            "all|{\"a\": {\"$type\": \"alias_x\", \"a\": \"x\"}}",
            "all|{\"k\": {\"number\": \"n\"}}",
            "all|{\"nm\": {\"r\": \"memo\"}}",
            "all|{\"oa\": {\"id\": \"x\", \"payment\": {\"number\": \"n\"}}}",
            "all|{\"oa\": {\"$type\": \"oa\", \"id\": \"x\", \"payment\": {\"number\": \"n\"}}}",
            "all|{\"oa\": {\"$type\": \"order_v1\", \"id\": \"x\", \"payment\": {\"number\": \"n\"}}}",
            "all|{\"els\": [{\"a\": \"x\"}, {\"$type\": \"rec_x\", \"a\": \"y\"}]}",
            "all|{\"m\": {\"k\": {\"a\": \"x\"}}}",
            "all|{\"keys\": {\"3\": \"three\"}}",
            "all|{\"tup\": [{\"a\": \"x\"}, \"t\"]}",
            "all|{\"dis\": {\"a\": \"x\"}}",
            "all|{\"dis\": 3}",
            "all|{\"dis\": {\"$type\": \"rec_x\", \"a\": \"x\"}}",
            "all|{\"pg\": {\"items\": [{\"a\": \"x\"}]}}",
            "all|{\"pd\": {\"items\": [{\"a\": \"x\"}]}}",
            "order_v1|{\"id\": \"x\", \"payment\": {\"number\": \"n\"}}",
            "order_v1|{\"$type\": \"order_v1\", \"id\": \"x\", \"payment\": {\"number\": \"n\"}}",
            "oa|{\"id\": \"x\", \"payment\": {\"number\": \"n\"}}",
            "count_x|3",
            "names_x|[\"a\", \"b\"]",
            "lookup_x|{\"$type\": 1}"})
    void aForeignValueReadsAndWritesBackAtEveryPositionAnExternTypes(String rootAndDocument) {
        String[] parts = rootAndDocument.split("\\|", 2);
        assertReadsBack(SHIPPING, parts[0], parts[1]);
    }

    /**
     * A value only its tag places -- at a choice, as a subtype, at an abstract target, at a declared position. A
     * schema-directed tree holds the value without its tag, as at every tagged position, so these read clean and are
     * not asked to read back.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "envelope|{\"id\": \"a\", \"body\": {\"$type\": \"order_v1\", \"$value\": {\"id\": \"x\", "
                    + "\"payment\": {\"number\": \"n\"}}}}",
            "envelope|{\"id\": \"a\", \"body\": {\"$type\": \"order_v1\", \"$value\": {\"$type\": \"order\", "
                    + "\"id\": \"x\", \"payment\": {\"$type\": \"card\", \"number\": \"n\"}}}}",
            "single|{\"o\": {\"$type\": \"rush_v2\", \"ref\": \"r\", \"lines\": [], \"payment\": {\"pan\": \"p\"}, "
                    + "\"by\": \"today\"}}",
            "all|{\"p\": {\"$type\": \"dog_x\", \"name\": \"rex\", \"barks\": true}}",
            "all|{\"p\": {\"$type\": \"dog_x\", \"$value\": {\"name\": \"rex\", \"barks\": true}}}",
            "all|{\"ct\": {\"$type\": \"email_x\", \"$value\": \"a@example.com\"}}",
            "all|{\"h\": {\"any\": {\"$type\": \"memo\", \"note\": \"n\"}}}",
            "all|{\"any\": {\"$type\": \"rec_x\", \"a\": \"x\"}}",
            "all|{\"any\": {\"$type\": \"count_x\", \"$value\": 3}}",
            "all|{\"any\": {\"$type\": \"lookup_x\", \"$value\": {\"x\": 1}}}"})
    void aForeignValueNamingItsTypeReadsAtEveryPositionAnExternTypes(String rootAndDocument) {
        String[] parts = rootAndDocument.split("\\|", 2);
        assertEquals(List.of(), shown(read(SHIPPING, parts[0], parts[1]).problems()), parts[1]);
    }

    @Test
    void scopesNestEachBoundaryReadInTheScopeEnclosingIt() {
        assertEquals(List.of(), shown(read(OUTER, "outer", """
                {"h": {"th": {"t": "x"}, "any": {"$type": "memo", "mid": "m"}}}""").problems()));
        assertReadsBack(OUTER, "outer", """
                {"h": {"$type": "holder_x", "th": {"$type": "thing_x", "t": "x"}}}""");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"o\": {\"$type\": \"order\", \"ref\": \"r\", \"lines\": [], \"payment\": {\"pan\": \"p\"}}}",
            "{\"o\": {\"$type\": \"rush\", \"ref\": \"r\", \"lines\": [], \"payment\": {\"pan\": \"p\"}, \"by\": \"b\"}}",
            "{\"o\": {\"$type\": \"express\", \"ref\": \"r\", \"lines\": [], \"payment\": {\"pan\": \"p\"}, "
                    + "\"at\": \"noon\"}}"})
    void aNameTheEnclosingScopeImportsIsAdmittedWhereItReachesTheTargetOrASubtype(String json) {
        assertEquals(List.of(), shown(read(IMPORTER, "holder", json).problems()), json);
    }

    @Test
    void aTreeHoldsTheForeignValueWithNoTagOfEitherScope() {
        Read read = read(SHIPPING, "single", """
                {"o": {"$type": "rush_v2", "ref": "r", "lines": [], "payment": {"$type": "card", "pan": "p"},
                 "by": "b"}}""");
        assertEquals(List.of(), shown(read.problems()));
        assertEquals("{\"o\":{\"ref\":\"r\",\"lines\":[],\"payment\":{\"pan\":\"p\"},\"by\":\"b\"}}",
                JSON.treeWriter().toJson(read.value()));
    }

    // ── refusals ─────────────────────────────────────────────────────────

    @Test
    void theForeignSchemasOwnNameIsRefusedAtTheBoundary() {
        List<Diagnostic> got = read(SHIPPING, "single", """
                {"o": {"$type": "order", "ref": "r", "lines": [], "payment": {"pan": "p"}}}""").problems();
        assertEquals(List.of("UNKNOWN_TYPE_REF /o: 'order' names no type of the enclosing scope, and a tag at a "
                + "'order_v2' position, which is an extern of 'order' in '" + ORDER_2 + "', is read there: the foreign "
                + "schema's own names are not, and a tag is admitted where it reaches the target, a subtype of it, or a "
                + "variant of it (order_v2 | rush_v2) ([TSON-SCHEMA] §7.9)"), shown(got));
        assertEquals(textRefusals(SHIPPING, "!single { o: !order { ref: r  lines: []  payment: { pan: p } } }"),
                refusals(got));
    }

    @Test
    void anOuterOnlyNameIsRefusedInside() {
        List<Diagnostic> got = read(SHIPPING, "all", """
                {"r": {"a": "x", "memo": {"$type": "holder_x", "any": {"$type": "memo", "note": "n"}}}}""")
                .problems();
        assertEquals(1, got.size(), shown(got).toString());
        assertEquals("/r/memo", got.getFirst().path().orElseThrow());
        assertTrue(got.getFirst().message().contains("holder_x")
                && got.getFirst().message().endsWith("(read in the scope of example.test/lib.tn, through the extern "
                + "'rec_x' of example.test/shipping.tn; [TSON-SCHEMA] §7.9)"), got.getFirst().message());
        assertEquals("example.test/lib.tn", got.getFirst().schemaId());
    }

    @Test
    void aTagOfAnotherExternIsRefused() {
        List<Diagnostic> got = read(SHIPPING, "single", """
                {"o": {"$type": "order_v1", "id": "x", "payment": {"number": "n"}}}""").problems();
        assertEquals(1, got.size(), shown(got).toString());
        assertTrue(shown(got).getFirst().startsWith("TYPE_MISMATCH /o: 'order_v1' is not admissible at a "
                + "'order_v2' position"), shown(got).getFirst());
        assertEquals(textRefusals(SHIPPING, "!single { o: !order_v1 { id: x  payment: { number: n } } }"),
                refusals(got));
    }

    @Test
    void aNameOfTheEnclosingScopeReachingAnotherTypeIsRefused() {
        List<Diagnostic> got = read(IMPORTER, "holder", """
                {"o": {"$type": "line", "sku": "s", "qty": 1}}""").problems();
        assertEquals(List.of("TYPE_MISMATCH /o: 'line' is not admissible at a 'o2' position, which is an extern of "
                + "'order' in '" + ORDER_2 + "': the tag is read in the enclosing scope, where 'line' reaches another "
                + "type, and a tag is admitted where it reaches the target, a subtype of it, or a variant of it "
                + "(order | rush | express | o2) ([TSON-SCHEMA] §7.9)"), shown(got));
        assertEquals(textRefusals(IMPORTER, "!holder { o: !line { sku: s  qty: 1 } }"), refusals(got));
    }

    @Test
    void aSupertypesExternIsRefusedAtASubtypesPosition() {
        List<Diagnostic> got = read(SHIPPING, "rushed", """
                {"o": {"$type": "order_v2", "ref": "r", "lines": [], "payment": {"pan": "p"}}}""").problems();
        assertEquals(1, got.size(), shown(got).toString());
        assertTrue(shown(got).getFirst().startsWith("TYPE_MISMATCH /o: 'order_v2' is not admissible"),
                shown(got).getFirst());
    }

    @Test
    void anUntaggedValueAtAnAbstractTargetWithNoSelectorsIsRefused() {
        List<Diagnostic> got = read(SHIPPING, "all", "{\"p\": {\"name\": \"rex\"}}").problems();
        assertEquals(1, got.size(), shown(got).toString());
        assertTrue(got.getFirst().message().contains("by a name of the enclosing scope, one of (dog_x)"),
                got.getFirst().message());
        assertEquals(textRefusals(SHIPPING, "!all { p: { name: rex } }"), refusals(got));
    }

    @Test
    void anImportInsideAForeignValueIsRefusedAsAtEveryPositionButTheTop() {
        List<Diagnostic> inside = read(SHIPPING, "all", """
                {"r": {"a": "x", "memo": {"$import": ["https://example.test/lib.tn"], "note": "n"}}}""").problems();
        assertEquals(List.of("SCOPE_NOT_ADMITTED /r/memo/$import"), refusals(inside));
        List<Diagnostic> boundary = read(SHIPPING, "all", """
                {"r": {"$import": ["https://example.test/lib.tn"], "$type": "rec_x", "a": "x"}}""").problems();
        assertEquals(List.of("SCOPE_NOT_ADMITTED /r/$import"), refusals(boundary));
    }

    @Test
    void anUntaggedValueAtAChoiceOfExternsIsRefused() {
        List<Diagnostic> got = read(SHIPPING, "envelope", """
                {"id": "a", "body": {"id": "x", "payment": {"number": "n"}}}""").problems();
        assertEquals(List.of("TYPE_MISMATCH /body"), refusals(got));
    }

    @Test
    void aDiagnosticInsideAForeignValueNamesTheForeignSchemaItsPointerAndTheExtern() {
        List<Diagnostic> got = read(SHIPPING, "single", """
                {"o": {"ref": "r", "lines": [{"sku": "s", "qty": 1, "extra": 2}], "payment": {"pan": "p"}}}""")
                .problems();
        assertEquals(1, got.size(), shown(got).toString());
        Diagnostic diagnostic = got.getFirst();
        assertEquals("example.test/order-2.tn", diagnostic.schemaId());
        assertEquals("/o/lines/0/extra", diagnostic.path().orElseThrow());
        assertTrue(diagnostic.schemaPointer().orElseThrow().startsWith("/order"), diagnostic.toString());
        assertTrue(diagnostic.message().endsWith("(read in the scope of example.test/order-2.tn, through the "
                + "extern 'order_v2' of example.test/shipping.tn; [TSON-SCHEMA] §7.9)"), diagnostic.message());
        assertEquals(textRefusals(SHIPPING, "!single { o: { ref: r  lines: [{ sku: s  qty: 1  extra: 2 }]  "
                + "payment: { pan: p } } }"), refusals(got));
    }

    @Test
    void aDiagnosticInANestedScopeNamesEveryBoundaryCrossed() {
        List<Diagnostic> got = read(OUTER, "outer", "{\"h\": {\"th\": {\"t\": 1}}}").problems();
        assertEquals(1, got.size(), shown(got).toString());
        assertEquals("example.test/third.tn", got.getFirst().schemaId());
        assertTrue(got.getFirst().message().endsWith("(read in the scope of example.test/third.tn, through the "
                + "extern 'thing_x' of example.test/mid.tn, within 'holder_x' of example.test/outer.tn; "
                + "[TSON-SCHEMA] §7.9)"), got.getFirst().message());
    }

    /**
     * A wrapper's {@code $value} is the foreign value, so a {@code $type} leading it is the foreign schema's and may
     * name a subtype of the target there, as a wrapper's value may at any record position (§3.3, §8.6).
     */
    @Test
    void aWrappersValueMayNameASubtypeOfTheTargetByTheForeignSchemasName() {
        assertEquals(List.of(), shown(read(SHIPPING, "single", """
                {"o": {"$type": "order_v2", "$value": {"$type": "rush", "ref": "r", "lines": [],
                 "payment": {"pan": "p"}, "by": "b"}}}""").problems()));
    }

    /**
     * The boundary's own tag naming an abstract target with no selectors selects nothing ([TSON-SCHEMA] §7.9), in
     * wrapper form as inline, whatever its {@code $value} holds, as a wrapper naming an abstract base is (§6.1.5).
     */
    @Test
    void aWrapperNamingAnAbstractTargetIsRefusedWhateverItsValueHolds() {
        List<Diagnostic> got = read(SHIPPING, "all", """
                {"p": {"$type": "pet_x", "$value": {"$type": "dog", "name": "rex", "barks": true}}}""").problems();
        assertEquals(List.of("TYPE_MISMATCH /p"), refusals(got));
        assertEquals(textRefusals(SHIPPING, "!all { p: !pet_x { name: rex } }"), refusals(got));
    }

    // ── one document ─────────────────────────────────────────────────────

    @Test
    void theLimitsRunAcrossTheBoundaryAsWithinOneDocument() {
        Json shallow = Json.of(config().withProcessorPolicy(ProcessorPolicy.defaults()
                .withLimits(LimitsPolicy.defaults().withMaxDepth(3)))).withSchemas(TSON.schemaRegistry());
        List<Diagnostic> problems = new ArrayList<>();
        shallow.treeReader().withSchema(SHIPPING).withDiagnostics(problems::add).readAs("""
                {"o": {"ref": "r", "lines": [{"sku": "s", "qty": 1}], "payment": {"pan": "p"}}}""", "single");
        assertEquals(Diagnostic.Code.LIMIT_EXCEEDED, problems.getFirst().code(), shown(problems).toString());
    }

    @Test
    void nameHygieneRunsInsideAForeignValueEachNameJudgedInTheScopeThatReadsIt() {
        List<Diagnostic> member = read(SHIPPING, "single", """
                {"o": {"ref": "r", "lines": [], "p\u0430yment": {"pan": "p"}}}""").problems();
        assertTrue(member.stream().anyMatch(d -> d.code() == Diagnostic.Code.RESTRICTED_SCRIPT
                || d.code() == Diagnostic.Code.CONFUSABLE_NAMES), shown(member).toString());
        assertTrue(member.stream().noneMatch(d -> d.code() == Diagnostic.Code.UNRECOGNIZED_FIELD),
                shown(member).toString());
        List<Diagnostic> boundary = read(SHIPPING, "single", """
                {"o": {"$type": "order\u0132v2", "ref": "r", "lines": [], "payment": {"pan": "p"}}}""").problems();
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_CHARACTER), boundary.stream().map(Diagnostic::code).toList());
    }
}
