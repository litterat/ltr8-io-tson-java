package io.ltr8.tson;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.tree.TsonValue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One header directive, {@code !!import}, and a root that says what a document is ([TSON-DATA] §2.2,
 * SPEC-FEEDBACK.md #8): a schema is a document whose root is tagged {@code !schema}, every import target is one, and a
 * data document imports as a schema does -- several schemas, read under one namespace over their closures.
 */
class OneDirectiveTest {

    private static final String CORE = "https://tson.io/2026/39/m/core.tn";
    private static final String MONEY = "https://example.test/money.tn";
    private static final String ORDERS = "https://example.test/orders.tn";
    private static final String OTHER_MONEY = "https://example.test/other-money.tn";
    private static final String NOT_A_SCHEMA = "https://example.test/a-value.tn";

    private static final Map<String, String> DOCUMENTS = Map.of(
            MONEY, """
                    !!id:"https://example.test/money.tn"
                    !!import:"https://tson.io/2026/39/m/core.tn"
                    !schema { money => { amount: number  currency: text } }""",
            ORDERS, """
                    !!id:"https://example.test/orders.tn"
                    !!import:"https://tson.io/2026/39/m/core.tn"
                    !schema { order => { sku: text  quantity: int32 } }""",
            OTHER_MONEY, """
                    !!id:"https://example.test/other-money.tn"
                    !!import:"https://tson.io/2026/39/m/core.tn"
                    !schema { money => { cents: int64 } }""",
            NOT_A_SCHEMA, """
                    !!id:"https://example.test/a-value.tn"
                    { amount: 5  currency: EUR }""");

    private static Tson tson() {
        SchemaSource source = uri -> DOCUMENTS.entrySet().stream()
                .filter(document -> CanonicalIdentity.sameIdentity(uri, document.getKey()))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new IllegalStateException("unexpected fetch: " + uri));
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
    }

    /** A data document may import several schemas, and its root names a type of any of them. */
    @Test
    void aDataDocumentReadsUnderEveryImport() {
        TsonValue order = tson().treeReader().read("""
                !!import:"https://example.test/money.tn"
                !!import:"https://example.test/orders.tn"
                !order { sku: "A-1"  quantity: 2 }""");

        assertEquals(2, order.get("quantity").asInt().orElseThrow());
        assertEquals(List.of(), tson().validate("""
                !!import:"https://example.test/money.tn"
                !!import:"https://example.test/orders.tn"
                !money { amount: 9.50  currency: EUR }"""));
    }

    /** Two imports declaring one name differently collide, by identity, as a schema's imports do (§2.2.3). */
    @Test
    void twoImportsDeclaringOneNameCollide() {
        List<Diagnostic> problems = tson().validate("""
                !!import:"https://example.test/money.tn"
                !!import:"https://example.test/other-money.tn"
                !order { sku: "A-1"  quantity: 2 }""");

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("'money' is declared by two different schemas"),
                problems.getFirst().message());
    }

    /** Every import target is a schema document: one whose root is not tagged {@code !schema} is refused. */
    @Test
    void importingADataDocumentIsRefused() {
        List<Diagnostic> problems = tson().validateSchema("""
                !!id:"https://example.test/importer.tn"
                !!import:"https://example.test/a-value.tn"
                !schema { thing => { a: int32 } }""");

        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(problem -> problem.message().contains("root is tagged '!schema'")),
                problems.toString());
    }

    /** An import-free schema is valid and sees its own names; the root's tag resolves against the kernel. */
    @Test
    void anImportFreeSchemaSeesOnlyItsOwnNames() {
        assertEquals(List.of(), tson().validateSchema("""
                !!id:"https://example.test/free.tn"
                !schema { a => { b: b }  b => {} }"""));

        // The kernel is pre-loaded to read the root, and its names still enter a namespace only by import.
        List<Diagnostic> problems = tson().validateSchema("""
                !!id:"https://example.test/free-2.tn"
                !schema { a => { b: int32 } }""");
        assertFalse(problems.isEmpty(), "int32 is core's, and this schema imports nothing");
    }

    /** A schema read where data is expected is the categorized refusal, classified by its root's tag. */
    @Test
    void aSchemaRootIsNotData() {
        List<Diagnostic> problems = tson().validate("""
                !!import:"https://tson.io/2026/39/m/core.tn"
                @doc:"orders"
                !schema { order => { sku: text } }""");

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("its root is tagged !schema"), problems.toString());
    }
}
