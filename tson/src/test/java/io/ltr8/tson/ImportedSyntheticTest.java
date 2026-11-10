package io.ltr8.tson;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.tree.TsonValue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A synthetic is identified by its form ([TSON-SCHEMA] §8.2), so two documents that each write {@code [text]} without
 * importing each other mint one entry, and a schema importing both takes it once: one name, one type, every use of
 * either document pointing at it. Two documents that each <em>declare</em> one name are still the collision §2.2.3
 * refuses.
 */
class ImportedSyntheticTest {

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

    /** Both libraries' {@code [text]} is one entry of the importer's namespace, and a field of each reads through it. */
    @Test
    void oneSyntheticMintedByTwoImportsIsOneEntry() {
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

    /**
     * A form one library lifts directly and another constructs by closing a template's held {@code [T]} is one entry
     * too, and a schema that writes the form itself, or closes the template again, reuses the imported entry.
     */
    @Test
    void aLiftedAndAClosedSyntheticAreOneEntry() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validateSchema("""
                !!id:"https://example.test/mixed.tn"
                !!import:"https://example.test/tags.tn"
                !!import:"https://example.test/listed.tn"
                !schema { mixed => { tagged: tagged  texts: texts  more: [text]  again: listed<text> } }"""));

        TsonValue mixed = tson.treeReader().read("""
                !!import:"https://example.test/mixed.tn"
                !mixed { tagged: { tags: [a] }  texts: { items: [b c] }  more: []  again: { items: [d] } }""");
        assertEquals(2, mixed.get("texts").get("items").elements().size());
        // tags.tn lifts [text] and listed.tn closes its [T] at text: one minted name, each document's own, and the
        // importer holds it once, the first import's.
        var core = tson.bindRegistry().core();
        String minted = ownClosedArray(core.resolveLinked("https://example.test/tags.tn"));
        assertEquals(minted, ownClosedArray(core.resolveLinked("https://example.test/listed.tn")));
        assertEquals("example.test/tags.tn", core.resolveLinked("https://example.test/mixed.tn").originOf(minted));
    }

    /** The one closed {@code [text]} synthetic a schema declares itself, an import's left out. */
    private static String ownClosedArray(TsonLinkedSchema linked) {
        String own = CanonicalIdentity.canonicalize(linked.schema().id());
        List<String> minted = linked.schema().entries().keySet().stream()
                .filter(name -> name.startsWith("array_text_") && own.equals(linked.originOf(name))).toList();
        assertEquals(1, minted.size(), minted::toString);
        return minted.getFirst();
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

    /** The name this library mints for {@code [text]}, read off a schema that writes it. */
    private static String mintedForTextArray() {
        return ownClosedArray(tson().bindRegistry().core().resolveLinked("https://example.test/tags.tn"));
    }

    /**
     * A document whose own lift derives a name an import already declares references the import's entry, so that
     * entry must be the form: an import marking a different form synthetic under the name is the broken naming
     * function, an invariant break rather than a silent reuse of the wrong type.
     */
    @Test
    void aLiftReusingAnImportedSyntheticOfAnotherFormIsAnInvariantBreak() {
        String minted = mintedForTextArray();
        Tson tson = tson(Map.of("https://example.test/forged.tn", """
                !!id:"https://example.test/forged.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema { @meta.synthetic %s => !meta.array { element_type: integer } }""".formatted(minted)));

        assertThrows(IllegalStateException.class, () -> tson.validateSchema("""
                !!id:"https://example.test/reuser.tn"
                !!import:"https://example.test/forged.tn"
                !schema { tagged => { tags: [text] } }"""));
    }

    /** An import <em>declaring</em> the name a lift derives is the collision of a declared entry and a minted one. */
    @Test
    void aLiftWhoseNameAnImportDeclaresCollides() {
        String minted = mintedForTextArray();
        Tson tson = tson(Map.of("https://example.test/declared.tn", """
                !!id:"https://example.test/declared.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema { %s => !meta.array { element_type: text } }""".formatted(minted)));

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/reuser.tn"
                !!import:"https://example.test/declared.tn"
                !schema { tagged => { tags: [text] } }""");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("as an entry of its own"), problems.toString());
    }

    /** The same lift reusing an imported synthetic of its own form loads, as every import of the kernel's does. */
    @Test
    void aLiftReusingAnImportedSyntheticOfItsOwnFormLoads() {
        assertEquals(List.of(), tson().validateSchema("""
                !!id:"https://example.test/reuser.tn"
                !!import:"https://example.test/shapes.tn"
                !schema {
                  more  => { either: (text | integer)  pairs: {text => integer}  tags: [text] }
                  again => <U> { items: [U]  either: (text | integer) }
                }"""));
    }
}
