package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a diagnostic calls a schema entry, for the entries the author never named.
 *
 * <p>Every §5.3 sugar form lifts to an entry of its own and every §5.10 application materialises one, both
 * named content-derived (§8.2) -- so a reader that used the entry name told an author their document broke
 * {@code 'array_order_1_e9777a39'}, a string appearing nowhere in their file and nowhere in the spec. These
 * fixtures pin what it says instead: the form they actually wrote.
 *
 * <p>They are read through the real pipeline rather than by calling the renderer, because the claim is about
 * what a <em>reader</em> reports, and the reader has to have been handed the right name to report.
 */
class SyntheticEntryNamingTest {

    private static final String ID = "https://example.test/naming.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/naming.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              order => { id: text }
              paged => <T> { items: [T; 1..] }
              order_response => paged<order>
              tag_list => [text; 1..2]
              holder => {
                tags:  tag_list
                inline: [order; 2..]
                ix:    {text => order; 1..}
                pair:  [text, int32]
                pick:  (text | int32)
                page:  paged<order>
              }
            }
            """;

    private static TsonCompiledSchema compiled() {
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledSchemaRegistry.tree(
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source)).get(ID);
    }

    /** Every problem in one read, so each fixture below is one assertion over the same compiled schema. */
    private static List<String> messagesFrom(String entry, String document) {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        compiled().get(entry).read(TestDocuments.document(document, problems));
        return problems.diagnostics().stream().map(Diagnostic::message).toList();
    }

    /**
     * Read through the <b>facade</b>, not the compiled reader directly, because that is where the root of a
     * pointer is decided: a compiled reader is shared by every name that reaches it and cannot know which
     * one this read came in through, so the facade seeds it from the name it looked up.
     */
    private static Diagnostic onlyDiagnostic(String document) {
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        new TsonTreeReader(TsonCompiledSchemaRegistry.tree(
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source)))
                .withDiagnostics(problems)
                .read("!!schema:\"" + ID + "\"\n" + document);
        assertEquals(1, problems.diagnostics().size(), problems.diagnostics()::toString);
        return problems.diagnostics().getFirst();
    }

    private static String only(String entry, String document) {
        List<String> messages = messagesFrom(entry, document);
        assertEquals(1, messages.size(), messages::toString);
        return messages.getFirst();
    }

    @Test
    void aLiftedArrayIsNamedByTheSugarThatLiftedIt() {
        assertTrue(only("holder", "{ tags: [\"a\"]  inline: []  ix: { \"k\" => { id: \"1\" } }  "
                        + "pair: [\"a\" 1]  pick: \"x\"  "
                        + "page: { items: [ { id: \"1\" } ] } }")
                        .startsWith("'[order; 2..]' has 0 elements"),
                "the field's own inline form, not the entry minted for it");
    }

    @Test
    void aLiftedMapAndTupleAndChoiceAreNamedTheSameWay() {
        assertTrue(only("holder", "{ tags: [\"a\"]  inline: [{ id: \"1\" } { id: \"2\" }]  ix: {}  "
                        + "pair: [\"a\" 1]  pick: \"x\"  "
                        + "page: { items: [ { id: \"1\" } ] } }")
                .startsWith("'{text => order; 1..}' has 0 entries"));
        assertTrue(only("holder", "{ tags: [\"a\"]  inline: [{ id: \"1\" } { id: \"2\" }]  "
                        + "ix: { \"k\" => { id: \"1\" } }  pair: [\"a\"]  pick: \"x\"  "
                        + "page: { items: [ { id: \"1\" } ] } }")
                .startsWith("'[text, int32]' has 2 positions"));
        assertTrue(only("holder", "{ tags: [\"a\"]  inline: [{ id: \"1\" } { id: \"2\" }]  "
                        + "ix: { \"k\" => { id: \"1\" } }  pair: [\"a\" 1]  pick: {}  "
                        + "page: { items: [ { id: \"1\" } ] } }")
                .startsWith("'(text | int32)' has no variant"));
    }

    /** The entry a template application materialises renders as the application, from its own §8.2 source. */
    @Test
    void anInstantiationEntryIsNamedByTheApplication() {
        assertEquals("unknown field 'extra' on 'paged<order>' -- a record is closed under its type (§7.2), "
                        + "whose fields are (items)",
                only("order_response", "{ items: [ { id: \"a\" } ]  extra: 1 }"));
    }

    // ── Where a diagnostic points, not just what it calls things ─────────

    /**
     * The pointer roots at the name the read entered through. {@code order_response} is an alias for the
     * entry the application materialised, and that entry's reader is shared by every name reaching it -- so
     * the root cannot come from the reader, and comes from the facade that looked the name up.
     */
    @Test
    void thePointerRootsAtTheNameTheReadEnteredThrough() {
        Diagnostic problem = onlyDiagnostic("!order_response { items: [] }");

        assertEquals(Optional.of("/order_response/items"), problem.schemaPointer());
    }

    /**
     * And it carries a line to open. A minted entry has no position of its own, and taking that absence
     * would answer "which line" with nothing -- so the nearest declaration that does have one stands:
     * the alias here, the enclosing record when the application sits at a field.
     */
    @Test
    void aTemplateDerivedProblemStillNamesALineTheAuthorCanOpen() {
        assertTrue(onlyDiagnostic("!order_response { items: [] }").schemaPosition().isPresent(),
                "the alias's own declaration");
        assertTrue(onlyDiagnostic("!holder { tags: [\"a\"]  inline: [{ id: \"1\" } { id: \"2\" }]  "
                        + "ix: { \"k\" => { id: \"1\" } }  pair: [\"a\" 1]  pick: \"x\"  "
                        + "page: { items: [] } }")
                        .schemaPosition().isPresent(),
                "the enclosing record's own declaration");
    }

    /** A declaration read directly still roots at itself, which is what it always did. */
    @Test
    void anOrdinaryDeclarationStillRootsAtItsOwnName() {
        assertEquals(Optional.of("/order/id"), onlyDiagnostic("!order { id: [] }").schemaPointer());
    }

    /** And a declaration keeps the name its author gave it, which is the whole distinction being drawn. */
    @Test
    void aDeclaredEntryIsStillNamedByItsOwnName() {
        assertTrue(only("holder", "{ tags: []  inline: [{ id: \"1\" } { id: \"2\" }]  "
                        + "ix: { \"k\" => { id: \"1\" } }  pair: [\"a\" 1]  pick: \"x\"  "
                        + "page: { items: [ { id: \"1\" } ] } }")
                .startsWith("'tag_list' has 0 elements"));
    }
}
