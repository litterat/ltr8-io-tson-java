package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.tree.TsonDocument;
import io.ltr8.tson.tree.TsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.ltr8.tson.ExternWorld.ORDER_1;
import static io.ltr8.tson.ExternWorld.ORDER_2;
import static io.ltr8.tson.ExternWorld.extern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value read through an extern ([TSON-SCHEMA] §7.9): its body in the foreign schema's scope, its tag and the
 * annotations in front of it in the scope that holds it, at every position an extern types.
 */
class ExternReadTest {

    static final String LIB = "https://example.test/lib.tn";
    static final String SHIPPING = "https://example.test/shipping.tn";
    static final String THIRD = "https://example.test/third.tn";
    static final String MID = "https://example.test/mid.tn";
    static final String OUTER = "https://example.test/outer.tn";
    static final String EXPRESS = "https://example.test/express.tn";
    static final String IMPORTER = "https://example.test/importer.tn";

    final ExternWorld world = new ExternWorld()
            .schema(LIB, """
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
                    """, ORDER_1)
            .schema(SHIPPING, extern("order_v1", ORDER_1, "order") + extern("order_v2", ORDER_2, "order")
                    + extern("rush_v2", ORDER_2, "rush") + extern("rec_x", LIB, "rec") + extern("pet_x", LIB, "pet")
                    + extern("dog_x", LIB, "dog") + extern("shape_x", LIB, "shape")
                    + extern("circle_x", LIB, "circle") + extern("count_x", LIB, "count")
                    + extern("colour_x", LIB, "colour") + extern("either_x", LIB, "either")
                    + extern("names_x", LIB, "names") + extern("lookup_x", LIB, "lookup")
                    + extern("pair_x", LIB, "pair") + extern("alias_x", LIB, "rec_alias")
                    + extern("holder_x", LIB, "holder") + extern("named_x", LIB, "named")
                    + extern("label_x", LIB, "label") + extern("card_x", LIB, "ext_card")
                    + extern("contact_x", LIB, "contact") + extern("email_x", LIB, "email") + """
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
                    """)
            .schema(THIRD, "thing => { t: text }\nmemo => { third: text }\n")
            .schema(MID, extern("thing_x", THIRD, "thing") + "holder => { th: thing_x  any?: meta.declared }\n"
                    + "memo => { mid: text }\n")
            .schema(OUTER, extern("holder_x", MID, "holder") + "outer => { h: holder_x }\nmemo => { outer: text }\n")
            .schema(EXPRESS, "express => order & { at: text }\n", ORDER_2)
            .schema(IMPORTER, extern("o2", ORDER_2, "order") + "holder => { o: o2 }\n", EXPRESS);

    private List<Diagnostic> problems(String schema, String data) {
        List<Diagnostic> reported = new ArrayList<>();
        world.tson().treeReader().withDiagnostics(reported::add).read("!!import:\"" + schema + "\"\n" + data);
        return reported;
    }

    private List<String> shipping(String data) {
        return problems(SHIPPING, data).stream().map(ExternWorld::shown).toList();
    }

    private void assertReadsBack(String schema, String data) {
        String text = "!!import:\"" + schema + "\"\n" + data;
        List<Diagnostic> reported = new ArrayList<>();
        TsonDocument read = world.tson().treeReader().withDiagnostics(reported::add).readDocument(text);
        assertEquals(List.of(), reported.stream().map(ExternWorld::shown).toList());
        String written = world.tson().treeWriter().toTson(read);
        List<Diagnostic> again = new ArrayList<>();
        TsonDocument reread = world.tson().treeReader().withDiagnostics(again::add).readDocument(written);
        assertEquals(List.of(), again.stream().map(ExternWorld::shown).toList(), written);
        assertEquals(read.root(), reread.root(), written);
    }

    @Test
    void theSection79ExampleReadsWithTheBodyInTheForeignScope() {
        assertReadsBack(SHIPPING, "!envelope { id: a  body: !order_v2 { ref: r  lines: [{ sku: A  qty: 1 }]  "
                + "payment: !card { pan: \"4111\" } } }");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "!envelope { id: a  body: !order_v1 { id: x  payment: { number: n } } }",
            "!single { o: { ref: r  lines: [!line { sku: s  qty: 1 }]  payment: { pan: p } } }",
            "!single { o: !order_v2 { ref: r  lines: []  payment: { pan: p } } }",
            "!single { o: !rush_v2 { ref: r  lines: []  payment: { pan: p }  by: today } }",
            "!all { r: { a: x  memo: !memo { note: n } } }",
            "!all { p: !dog_x { name: rex  barks: true } }",
            "!all { s: { kind: circle  size: 3 } }",
            "!all { s: !circle_x { kind: circle  size: 3 } }",
            "!all { c: 3 }",
            "!all { col: green }",
            "!all { e: 4 }",
            "!all { e: !either_x 4 }",
            "!all { e: !int32 4 }",
            "!all { s: !shape_x { kind: circle  size: 3 } }",
            "!all { ct: !email_x \"a@example.com\" }",
            "!all { n: [a b] }",
            "!all { l: { x => 1 } }",
            "!all { t: [a 1] }",
            "!all { a: { a: x } }",
            "!all { a: !alias_x { a: x } }",
            "!all { k: { number: n } }",
            "!all { h: { any: !memo { note: n } } }",
            "!all { nm: { r: memo } }",
            "!all { oa: { id: x  payment: { number: n } } }",
            "!all { oa: !oa { id: x  payment: { number: n } } }",
            "!all { oa: !order_v1 { id: x  payment: { number: n } } }",
            "!all { els: [{ a: x } !rec_x { a: y }] }",
            "!all { m: { k => { a: x } } }",
            "!all { keys: { 3 => three } }",
            "!all { tup: [{ a: x } t] }",
            "!all { dis: { a: x } }",
            "!all { dis: 3 }",
            "!all { any: !rec_x { a: x } }",
            "!all { pg: { items: [{ a: x }] } }",
            "!all { pd: { items: [{ a: x }] } }",
            "!order_v1 { id: x  payment: { number: n } }",
            "!count_x 3",
            "!names_x [a b]",
            "@label_x:\"hello\" !all { r: { a: x } }",
            "!all { r: @label_x:\"hello\" { a: x } }",
            "!all { r: @label_x:\"hello\" !rec_x { a: x } }"})
    void aForeignValueReadsAndWritesBackAtEveryPositionAnExternTypes(String data) {
        assertReadsBack(SHIPPING, data);
    }

    @Test
    void scopesNestEachBoundaryReadInTheScopeEnclosingIt() {
        assertReadsBack(OUTER, "!outer { h: { th: { t: x }  any: !memo { mid: m } } }");
        assertReadsBack(OUTER, "!outer { h: !holder_x { th: !thing_x { t: x } } }");
    }

    @Test
    void aTreeValueAtTheBoundaryCarriesTheOuterNameAndTheNamesInsideTheForeignOnes() {
        TsonValue root = world.tson().treeReader().read("!!import:\"" + SHIPPING + "\"\n"
                + "!single { o: @label_x:\"l\" !rush_v2 { ref: r  lines: [{ sku: s  qty: 1 }]  payment: { pan: p } "
                + " by: b } }");
        assertEquals(Optional.of("rush_v2"), root.get("o").typeRef());
        assertEquals("label_x", root.get("o").annotations().getFirst().name());
        assertEquals(Optional.of("line"), root.at("/o/lines/0").typeRef());
        assertEquals(Optional.of("card"), root.at("/o/payment").typeRef());
    }

    @Test
    void anAnnotationThroughAnExternInASchemaDocumentIsReadAgainstTheTargetInItsScope() {
        assertEquals(List.of(), world.validate(extern("label_x", LIB, "label") + extern("rec_x", LIB, "rec")
                + "memo => { other: int32 }\n@label_x:\"hello\" y => { a: text }\n"
                + "z => @rec_x:{ a: x  memo: !memo { note: n } } { b: text }\n"));
    }

    @Test
    void anAnnotationsValueThroughAnExternIsJudgedByTheTargetInItsScope() {
        List<String> got = world.validate(extern("count_x", LIB, "count") + extern("rec_x", LIB, "rec")
                + "memo => { other: int32 }\n@count_x:-1 y => { a: text }\n"
                + "z => @rec_x:{ a: x  memo: !memo { other: 1 } } { b: text }\n");
        assertEquals(2, got.size(), String.join("\n", got));
        assertTrue(got.stream().anyMatch(line -> line.startsWith("SCHEMA_ERROR /y:") && line.contains("'-1' is "
                + "less than the minimum 0 (read in the scope of example.test/lib.tn, through the extern 'count_x' of "
                + "example.test/subject.tn; [TSON-SCHEMA] §7.9)")), String.join("\n", got));
        assertTrue(got.stream().anyMatch(line -> line.startsWith("SCHEMA_ERROR /z:")
                && line.contains("unknown field 'other' on 'memo'")), String.join("\n", got));
    }

    // ── refusals ─────────────────────────────────────────────────────────

    @Test
    void theForeignSchemasOwnNameIsRefusedAtTheBoundary() {
        List<String> got = shipping("!single { o: !order { ref: r  lines: []  payment: { pan: p } } }");
        assertEquals(1, got.size(), String.join("\n", got));
        assertEquals("UNKNOWN_TYPE_REF /single/o: 'order' names no type of the enclosing scope, and a tag at a "
                + "'order_v2' position, which is an extern of 'order' in '" + ORDER_2 + "', is read there: the foreign "
                + "schema's own names are not, and a tag is admitted where it reaches the target, a subtype of it, or a "
                + "variant of it (order_v2 | rush_v2) ([TSON-SCHEMA] §7.9)", got.getFirst());
    }

    @Test
    void anOuterOnlyNameIsRefusedInside() {
        List<String> got = shipping("!all { r: { a: x  memo: !holder_x { any: !memo { note: n } } } }");
        assertTrue(got.stream().anyMatch(line -> line.contains("holder_x") && line.contains("[TSON-SCHEMA] §7.9")),
                String.join("\n", got));
    }

    @Test
    void aTagOfAnotherExternIsRefused() {
        List<String> got = shipping("!single { o: !order_v1 { id: x  payment: { number: n } } }");
        assertEquals(1, got.size(), String.join("\n", got));
        assertTrue(got.getFirst().startsWith("TYPE_MISMATCH /single/o: 'order_v1' is not admissible at a "
                + "'order_v2' position"), got.getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "!holder { o: !order { ref: r  lines: []  payment: { pan: p } } }",
            "!holder { o: !rush { ref: r  lines: []  payment: { pan: p }  by: b } }",
            "!holder { o: !express { ref: r  lines: []  payment: { pan: p }  at: noon } }"})
    void aNameTheEnclosingScopeImportsIsAdmittedWhereItReachesTheTargetOrASubtype(String data) {
        assertReadsBack(IMPORTER, data);
    }

    @Test
    void aNameOfTheEnclosingScopeReachingAnotherTypeIsRefused() {
        List<String> got = problems(IMPORTER, "!holder { o: !line { sku: s  qty: 1 } }").stream()
                .map(ExternWorld::shown).toList();
        assertEquals(List.of("TYPE_MISMATCH /holder/o: 'line' is not admissible at a 'o2' position, which is an "
                + "extern of 'order' in '" + ORDER_2 + "': the tag is read in the enclosing scope, where 'line' reaches "
                + "another type, and a tag is admitted where it reaches the target, a subtype of it, or a variant of it "
                + "(order | rush | express | o2) ([TSON-SCHEMA] §7.9)"), got);
        assertEquals(1, shipping("!all { e: !uuid 4 }").size());
    }

    @Test
    void aSupertypesExternIsRefusedAtASubtypesPosition() {
        List<String> got = shipping("!rushed { o: !order_v2 { ref: r  lines: []  payment: { pan: p } } }");
        assertEquals(1, got.size(), String.join("\n", got));
        assertTrue(got.getFirst().startsWith("TYPE_MISMATCH /rushed/o: 'order_v2' is not admissible"),
                got.getFirst());
    }

    @Test
    void anUntaggedValueAtAnAbstractTargetWithNoSelectorsIsRefused() {
        List<String> got = shipping("!all { p: { name: rex } }");
        assertEquals(1, got.size(), String.join("\n", got));
        assertTrue(got.getFirst().contains("by a name of the enclosing scope, one of (dog_x)"), got.getFirst());
    }

    @Test
    void aDiagnosticInsideAForeignValueNamesTheForeignSchemaItsPointerAndTheExtern() {
        List<Diagnostic> got = problems(SHIPPING, "!single { o: { ref: r  lines: [{ sku: s  qty: x }]  "
                + "payment: { pan: p } } }");
        assertEquals(1, got.size(), got.toString());
        Diagnostic diagnostic = got.getFirst();
        assertEquals("example.test/order-2.tn", diagnostic.schemaId());
        assertEquals(Optional.of("/o/lines/0/qty"), diagnostic.path().map(path -> path.substring("/".length() - 1))
                .map(path -> path.replaceFirst("^/single", "")));
        assertTrue(diagnostic.schemaPointer().orElseThrow().startsWith("/order"), diagnostic.toString());
        assertTrue(diagnostic.message().endsWith("(read in the scope of example.test/order-2.tn, through the "
                + "extern 'order_v2' of example.test/shipping.tn; [TSON-SCHEMA] §7.9)"), diagnostic.message());
    }
}
