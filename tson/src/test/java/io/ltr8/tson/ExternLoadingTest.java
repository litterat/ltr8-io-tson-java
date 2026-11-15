package io.ltr8.tson;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.compiler.TsonContentHash;
import io.ltr8.tson.schema.TsonLinkedSchema;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static io.ltr8.tson.ExternWorld.ORDER_1;
import static io.ltr8.tson.ExternWorld.ORDER_2;
import static io.ltr8.tson.ExternWorld.extern;
import static io.ltr8.tson.ExternWorld.joined;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An extern's schema is a dependency loaded with the imports ([TSON-SCHEMA] §3.4.1, §5.13): found by the written head
 * before any name resolves, under the same source and pin verification as an import, and refused, through the extern,
 * where it does not load, holds another kernel, or closes a cycle over imports and externs together.
 */
class ExternLoadingTest {

    private final ExternWorld world = new ExternWorld();

    private static String only(List<String> diagnostics) {
        assertEquals(1, diagnostics.size(), () -> joined(diagnostics));
        return diagnostics.getFirst();
    }

    @Test
    void twoSchemasDeclaringOneNameAreNamedSideBySide() {
        assertEquals(List.of(), world.validate(extern("order_v1", ORDER_1, "order") + extern("order_v2", ORDER_2,
                "order") + "envelope => { id: text  body: (order_v1 | order_v2) }"));
    }

    @Test
    void theLinkedSchemaListsItsExternsSchemasBesideItsImports() {
        Tson tson = world.tson();
        TsonLinkedSchema linked = tson.resolve(ExternWorld.text("https://example.test/subject.tn",
                extern("a", ORDER_1, "order") + extern("b", ORDER_2, "order") + extern("c", ORDER_1, "card")));
        assertEquals(List.of(CanonicalIdentity.canonicalize(ORDER_1), CanonicalIdentity.canonicalize(ORDER_2)),
                linked.externSchemas());
        assertTrue(tson.schemaRegistry().get(ORDER_1).isPresent(), "an extern's schema is registered as an import is");
    }

    @Test
    void theDependencyListIsEveryImportThenEveryExternsSchemaEachOnce() {
        world.schema("https://example.test/shared.tn", "money => { cents: int64 }\n");
        TsonLinkedSchema linked = world.tson().resolve(ExternWorld.text("https://example.test/subject.tn",
                extern("a", ORDER_1, "order") + extern("b", ORDER_2, "order") + extern("c", ORDER_1, "card"),
                "https://example.test/shared.tn"));
        assertEquals(List.of(CanonicalIdentity.canonicalize(ExternWorld.CORE),
                CanonicalIdentity.canonicalize("https://example.test/shared.tn"), CanonicalIdentity.canonicalize(ORDER_1),
                CanonicalIdentity.canonicalize(ORDER_2)), linked.dependencies());
    }

    @Test
    void noneOfTheForeignSchemasNamesEntersTheNamespace() {
        assertEquals(List.of("SCHEMA_ERROR /batch: 'batch' field 'c' has an unresolved reference 'card'"),
                world.validate(extern("o1", ORDER_1, "order") + "batch => { o: o1  c: card }"));
    }

    @Test
    void aSchemaImportingOneWithExternsHasTheExternNamesAndNoneOfTheForeign() {
        world.schema("https://example.test/envelope.tn", extern("o1", ORDER_1, "order") + extern("o2", ORDER_2,
                "order") + "envelope => { body: (o1 | o2) }");
        assertEquals(List.of(), world.validate("batch => { items: [o1]  envs: [envelope]  either: (o1 | o2) }",
                "https://example.test/envelope.tn"));
        assertEquals(List.of("SCHEMA_ERROR /batch: 'batch' field 'c' has an unresolved reference 'card'"),
                world.validate("batch => { c: card }", "https://example.test/envelope.tn"));
    }

    @Test
    void anExternInANestedSchemaMapIsCollectedToo() {
        assertEquals(List.of(), world.validate("v1 => !schema {\n" + extern("order", ORDER_1, "order") + "}\n"
                + "v2 => !schema {\n" + extern("order", ORDER_2, "order") + "}\n"
                + "envelope => { body: (v1.order | v2.order) }"));
    }

    @Test
    void aForeignSchemaImportingNothingIsAdmitted() {
        world.documents.put("https://example.test/free.tn",
                "!!id:\"https://example.test/free.tn\"\n!schema { a => { b: b }  b => {} }\n");
        assertEquals(List.of(), world.validate(extern("free_a", "https://example.test/free.tn", "a")
                + "use => { f: free_a }"));
    }

    @Test
    void anExternsSchemaIsAnyReferenceAnImportMayBe() {
        world.documents.put("/local/orders.tn", "!!id:\"/local/orders.tn\"\n!schema { order => { id: order_id }"
                + "  order_id => {} }\n");
        for (String spelling : List.of("/local/orders.tn", "file:/local/orders.tn", "file:///local/orders.tn")) {
            assertEquals(List.of(), world.validate(extern("local_order", spelling, "order")
                    + "use => { f: local_order }"), spelling);
        }
        assertEquals(List.of(), world.validate("use => { f: order }", "/local/orders.tn"),
                "the import an extern of the same reference stands beside");
    }

    @Test
    void anExternsSchemaThatIsNoIdentityIsRefusedAsAnImportsIs() {
        String relative = only(world.validate(extern("x", "local/orders.tn", "order")));
        assertTrue(relative.contains("'x' is an extern of 'order' in 'local/orders.tn'"), relative);
        String fragment = only(world.validate(extern("x", ORDER_1 + "#order", "order")));
        assertTrue(fragment.contains("'x' is an extern of 'order' in '" + ORDER_1 + "#order'"), fragment);
    }

    @Test
    void oneDigestPerIdentityHoldsAcrossAnImportAndAnExtern() {
        String digest = TsonContentHash.sha256(world.documents.get(ORDER_1).getBytes(StandardCharsets.UTF_8));
        String right = ORDER_1 + "?sha256=" + digest;
        String wrong = ORDER_1 + "?sha256=" + "0".repeat(64);
        assertEquals(List.of(), world.validate(extern("o1", right, "order") + "use => { a: o1  b: order }", right));
        String refused = only(world.validate(extern("o1", wrong, "order") + "use => { a: o1  b: order }", right));
        assertTrue(refused.startsWith("SCHEMA_ERROR : 'o1' is an extern of 'order' in '" + wrong
                + "', whose schema does not verify: content hash mismatch"), refused);
    }

    @Test
    void anExternWhoseSchemaIsNotAvailableIsReportedThroughTheExtern() {
        String unavailable = only(world.validate(extern("x", "https://example.test/none.tn", "a")));
        assertTrue(unavailable.startsWith("SCHEMA_NOT_FOUND : cannot fetch schema 'https://example.test/none.tn': "
                + "'x' is an extern of 'a' in 'https://example.test/none.tn', whose schema is not available"),
                unavailable);
    }

    @Test
    void anExternWhoseSchemaDoesNotResolveRefusesTheImporter() {
        world.schema("https://example.test/broken.tn", "thing => { t: nowhere }");
        String refused = only(world.validate(extern("x", "https://example.test/broken.tn", "thing")));
        assertTrue(refused.startsWith("SCHEMA_ERROR : 'x' is an extern of 'thing' in 'https://example.test/broken.tn'"
                + ", whose schema does not load: "), refused);
        assertTrue(refused.contains("'nowhere'"), refused);
    }

    @Test
    void externsNamingEachOthersSchemasAreACycleNamedByItsExternEdges() {
        world.schema("https://example.test/g.tn", "g => { f: ff }\n" + extern("ff", "https://example.test/f.tn", "f"));
        world.schema("https://example.test/f.tn", "f => { g?: gg }\n" + extern("gg", "https://example.test/g.tn", "g"));
        String refused = cycleThrough("https://example.test/g.tn");
        assertTrue(refused.contains("is part of a reference cycle through an extern (example.test/g.tn -[extern "
                + "'ff']-> example.test/f.tn -[extern 'gg']-> example.test/g.tn)"), refused);
    }

    @Test
    void anImportOneWayAndAnExternTheOtherAreACycle() {
        world.schema("https://example.test/g2.tn", "g => { f: f }", "https://example.test/f2.tn");
        world.schema("https://example.test/f2.tn", "f => { g?: gg }\n"
                + extern("gg", "https://example.test/g2.tn", "g"));
        String refused = cycleThrough("https://example.test/g2.tn");
        assertTrue(refused.contains("(example.test/g2.tn -> example.test/f2.tn -[extern 'gg']-> example.test/g2.tn)"),
                refused);
    }

    private String cycleThrough(String uri) {
        Tson tson = world.tson();
        List<String> messages = new ArrayList<>();
        try {
            tson.treeRegistry().get(uri);
        } catch (RuntimeException refused) {
            messages.add(refused.getMessage());
        }
        assertEquals(1, messages.size(), "a cycle refuses the schema");
        return messages.getFirst();
    }

    @Test
    void aForeignClosureHoldingAnotherKernelIsRefusedNamingBothKernels() {
        world.schema("https://example.test/old.tn", "thing => { t: text }", "https://tson.io/2026/39/m/meta.tn");
        assertEquals(List.of("SCHEMA_ERROR : 'x' is an extern of 'thing' in 'https://example.test/old.tn', whose "
                        + "schema does not load: its closure holds the kernel 'tson.io/2026/39/m/meta.tn', and the "
                        + "schema naming it holds the kernel 'tson.io/2026/40/m/meta.tn' -- an extern's schema holds "
                        + "the importer's kernel, one kernel by identity ([TSON-SCHEMA] §5.13)"),
                world.validate(extern("x", "https://example.test/old.tn", "thing")));
    }

    @Test
    void anAliasOfTheConstructorIsNoExternHead() {
        assertEquals(List.of("SCHEMA_ERROR /order_v1: 'order_v1': '!ext' reaches 'meta.extern' through its alias "
                        + "hops, and an extern is written '!meta.extern': its head is how a document's externs are "
                        + "found before any name resolves ([TSON-SCHEMA] §5.13)"),
                world.validate("ext => meta.extern\norder_v1 => !ext { schema: \"" + ORDER_1 + "\"  type: order }"));
    }

    @Test
    void anImportFreeSchemasOwnMetaExternIsNoExtern() {
        List<String> diagnostics = world.validateText("""
                !!id:"https://example.test/free.tn"
                !schema {
                  meta => !schema { extern => { schema?: s } }
                  s => {}
                  x => !meta.extern { schema: "https://example.test/none.tn" }
                }
                """);
        assertFalse(diagnostics.stream().anyMatch(diagnostic -> diagnostic.startsWith("SCHEMA_NOT_FOUND")),
                "nothing is fetched for a construction of the schema's own entry: " + joined(diagnostics));
    }
}
