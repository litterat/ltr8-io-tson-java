package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a diagnostic calls a type written in place ([TSON-SCHEMA] §8.2): no entry and no name, so the form the author
 * wrote, {@code [order; 2..]}, and a declaration by the name its author gave it.
 *
 * <p>They are read through the real pipeline rather than by calling the renderer, because the claim is about
 * what a <em>reader</em> reports, and the reader has to have been handed the right name to report.
 */
class InlineTypeNamingTest {

    private static final String ID = "https://example.test/naming.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/naming.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              order => { id: text }
              paged => <T> { items: [T] }
              order_response => paged<order>
              tag_list => [text; 1..2]
              holder => {
                tags:  tag_list
                inline: [order; 2..]
                ix:    {text => order; 1..}
                pair:  [text, int32]
                pick:  (text | int32)
                page:  order_response
              }
            }
            """;

    private static TsonCompiledSchema compiled() {
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledSchemaRegistry.tree(
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source)).get(ID);
    }

    /** Every problem in one read, so each fixture below is one assertion over the same compiled schema. */
    private static List<String> messagesFrom(String entry, String document) {
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        compiled().get(entry).read(TestDocuments.document(document, problems));
        return problems.diagnostics().stream().map(Diagnostic::message).toList();
    }

    /**
     * Read through the <b>facade</b>, not the compiled reader directly, because that is where the root of a
     * pointer is decided: a compiled reader is shared by every name that reaches it and cannot know which
     * one this read came in through, so the facade seeds it from the name it looked up.
     */
    private static Diagnostic onlyDiagnostic(String document) {
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        new TsonTreeReader(TsonCompiledSchemaRegistry.tree(
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source)))
                .withDiagnostics(problems)
                .read("!!import:\"" + ID + "\"\n" + document);
        assertEquals(1, problems.diagnostics().size(), problems.diagnostics()::toString);
        return problems.diagnostics().getFirst();
    }

    private static String only(String entry, String document) {
        List<String> messages = messagesFrom(entry, document);
        assertEquals(1, messages.size(), messages::toString);
        return messages.getFirst();
    }

    @Test
    void anArrayInPlaceIsNamedByItsForm() {
        assertTrue(only("holder", "{ tags: [\"a\"]  inline: []  ix: { \"k\" => { id: \"1\" } }  "
                        + "pair: [\"a\" 1]  pick: \"x\"  "
                        + "page: { items: [ { id: \"1\" } ] } }")
                        .startsWith("'[order; 2..]' has 0 elements"),
                "the field's own form");
    }

    @Test
    void aMapATupleAndAChoiceInPlaceAreNamedTheSameWay() {
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

    /**
     * A declared application is named by its declaration: {@code order_response => paged<order>} <b>is</b> the
     * closed record (§8.2), so the reader holds the name the author wrote, a name the author can open.
     */
    @Test
    void aDeclaredApplicationIsNamedByItsDeclaration() {
        assertEquals("unknown field 'extra' on 'order_response' -- a record is closed under its type (§7.2), "
                        + "whose fields are (items)",
                only("order_response", "{ items: [ { id: \"a\" } ]  extra: 1 }"));
    }

    // ── Where a diagnostic points, not just what it calls things ─────────

    /**
     * The pointer roots at the name the read entered through. A compiled reader is shared by every name that reaches
     * it, so the root cannot come from the reader, and comes from the facade that looked the name up.
     */
    @Test
    void thePointerRootsAtTheNameTheReadEnteredThrough() {
        Diagnostic problem = onlyDiagnostic("!order_response { items: 3 }");

        assertEquals(Optional.of("/order_response/items"), problem.schemaPointer());
    }

    /**
     * And it carries a line to open, whether the read entered at the declaration or reached it through another
     * record's field.
     */
    @Test
    void aProblemInsideADeclaredApplicationNamesALineTheAuthorCanOpen() {
        assertTrue(onlyDiagnostic("!order_response { items: 3 }").schemaPosition().isPresent(),
                "the declaration's own line");
        assertTrue(onlyDiagnostic("!holder { tags: [\"a\"]  inline: [{ id: \"1\" } { id: \"2\" }]  "
                        + "ix: { \"k\" => { id: \"1\" } }  pair: [\"a\" 1]  pick: \"x\"  "
                        + "page: { items: 3 } }")
                        .schemaPosition().isPresent(),
                "the enclosing record's own declaration");
    }

    /** A declaration read directly roots at itself. */
    @Test
    void aDeclarationRootsAtItsOwnName() {
        assertEquals(Optional.of("/order/id"), onlyDiagnostic("!order { id: [] }").schemaPointer());
    }

    /** And a declaration keeps the name its author gave it, where a type in place is named by its form. */
    @Test
    void aDeclarationIsNamedByItsOwnName() {
        assertTrue(only("holder", "{ tags: []  inline: [{ id: \"1\" } { id: \"2\" }]  "
                        + "ix: { \"k\" => { id: \"1\" } }  pair: [\"a\" 1]  pick: \"x\"  "
                        + "page: { items: [ { id: \"1\" } ] } }")
                .startsWith("'tag_list' has 0 elements"));
    }
}
