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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type written in place is no entry ([TSON-SCHEMA] §8.2): two documents that each write {@code [text]} without
 * importing each other add nothing to the namespace for it, so a schema importing both, and writing {@code [text]}
 * itself, reads through each. Two documents that each <em>declare</em> one name are still the collision §2.2.3
 * refuses.
 */
class ImportedInlineTypesTest {

    private static final Map<String, String> DOCUMENTS = Map.of(
            "https://example.test/tags.tn", """
                    !!id:"https://example.test/tags.tn"
                    !!import:"https://tson.io/2026/40/m/meta.tn"
                    !schema { tagged => { tags: [text] } }""",
            "https://example.test/labels.tn", """
                    !!id:"https://example.test/labels.tn"
                    !!import:"https://tson.io/2026/40/m/meta.tn"
                    !schema { labelled => { labels: [text] } }""",
            "https://example.test/listed.tn", """
                    !!id:"https://example.test/listed.tn"
                    !!import:"https://tson.io/2026/40/m/meta.tn"
                    !schema { listed => <T> { items: [T] }  texts => listed<text> }""",
            "https://example.test/thing-one.tn", """
                    !!id:"https://example.test/thing-one.tn"
                    !!import:"https://tson.io/2026/40/m/meta.tn"
                    !schema { thing => { tags: [text] } }""",
            "https://example.test/thing-two.tn", """
                    !!id:"https://example.test/thing-two.tn"
                    !!import:"https://tson.io/2026/40/m/meta.tn"
                    !schema { thing => { tags: [text] } }""",
            "https://example.test/shapes.tn", """
                    !!id:"https://example.test/shapes.tn"
                    !!import:"https://tson.io/2026/40/m/meta.tn"
                    !schema {
                      shaped => { either: (text | integer)  pairs: {text => integer}  tags: [text] }
                      held   => <T> { items: [T]  either: (text | integer) }
                    }""");

    private static Tson tson() {
        return tson(DOCUMENTS);
    }

    private static Tson tson(Map<String, String> documents) {
        SchemaSource source = uri -> documents.entrySet().stream()
                .filter(document -> CanonicalIdentity.sameIdentity(uri, document.getKey()))
                .map(Map.Entry::getValue).findFirst()
                .orElseThrow(() -> new IllegalStateException("unexpected fetch: " + uri));
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
    }

    /** Each library's {@code [text]} stays in its own field, and a field of each reads through it. */
    @Test
    void anArrayInPlaceInTwoImportsReadsThroughEach() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema("""
                !!id:"https://example.test/both.tn"
                !!import:"https://example.test/tags.tn"
                !!import:"https://example.test/labels.tn"
                !schema { both => { tagged: tagged  labelled: labelled  more: [text] } }"""));

        TsonValue both = tson.treeReader().read("""
                !!import:"https://example.test/both.tn"
                !both { tagged: { tags: [a b] }  labelled: { labels: [c] }  more: [d] }""");
        assertEquals(2, both.get("tagged").get("tags").elements().size());
        assertEquals(1, both.get("labelled").get("labels").elements().size());
    }

    /** A data document importing both libraries reads under one namespace over both closures. */
    @Test
    void aDataDocumentImportingBothReads() {
        List<Diagnostic> problems = tson().validate("""
                !!import:"https://example.test/tags.tn"
                !!import:"https://example.test/labels.tn"
                !tagged { tags: [a b] }""");

        assertEquals(List.of(), problems);
    }

    /** Two schemas declaring one name are two types, however alike their bodies, and importing both is refused. */
    @Test
    void twoDeclaredEntriesOfOneNameStillCollide() {
        List<Diagnostic> problems = tson().validateSchema("""
                !!id:"https://example.test/things.tn"
                !!import:"https://example.test/thing-one.tn"
                !!import:"https://example.test/thing-two.tn"
                !schema { box => { thing: thing } }""");

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("'thing' is declared by two different schemas"),
                problems.toString());
    }

    /** A schema writing in place the forms an import writes in place loads: neither is an entry to collide. */
    @Test
    void writingTheFormsAnImportWritesInPlaceLoads() {
        assertEquals(List.of(), tson().validateSchema("""
                !!id:"https://example.test/reuser.tn"
                !!import:"https://example.test/shapes.tn"
                !schema {
                  more  => { either: (text | integer)  pairs: {text => integer}  tags: [text] }
                  again => <U> { items: [U]  either: (text | integer) }
                }"""));
    }
}
