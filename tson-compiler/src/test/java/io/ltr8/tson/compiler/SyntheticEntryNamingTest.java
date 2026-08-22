package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static String only(String entry, String document) {
        List<String> messages = messagesFrom(entry, document);
        assertEquals(1, messages.size(), messages::toString);
        return messages.getFirst();
    }

    @Test
    void aLiftedArrayIsNamedByTheSugarThatLiftedIt() {
        assertTrue(only("holder", "{ tags: [\"a\"]  inline: []  ix: { \"k\" => { id: \"1\" } }  "
                        + "pair: [\"a\" 1]  pick: \"x\" }")
                        .startsWith("'[order; 2..]' has 0 elements"),
                "the field's own inline form, not the entry minted for it");
    }

    @Test
    void aLiftedMapAndTupleAndChoiceAreNamedTheSameWay() {
        assertTrue(only("holder", "{ tags: [\"a\"]  inline: [{ id: \"1\" } { id: \"2\" }]  ix: {}  "
                        + "pair: [\"a\" 1]  pick: \"x\" }")
                .startsWith("'{text => order; 1..}' has 0 entries"));
        assertTrue(only("holder", "{ tags: [\"a\"]  inline: [{ id: \"1\" } { id: \"2\" }]  "
                        + "ix: { \"k\" => { id: \"1\" } }  pair: [\"a\"]  pick: \"x\" }")
                .startsWith("'[text, int32]' has 2 positions"));
        assertTrue(only("holder", "{ tags: [\"a\"]  inline: [{ id: \"1\" } { id: \"2\" }]  "
                        + "ix: { \"k\" => { id: \"1\" } }  pair: [\"a\" 1]  pick: {} }")
                .startsWith("'(text | int32)' has no variant"));
    }

    /** The entry a template application materialises renders as the application, from its own §8.2 source. */
    @Test
    void anInstantiationEntryIsNamedByTheApplication() {
        assertEquals("unknown field 'extra' on 'paged<order>' -- a record is closed under its type (§7.2), "
                        + "whose fields are (items)",
                only("order_response", "{ items: [ { id: \"a\" } ]  extra: 1 }"));
    }

    /** And a declaration keeps the name its author gave it, which is the whole distinction being drawn. */
    @Test
    void aDeclaredEntryIsStillNamedByItsOwnName() {
        assertTrue(only("holder", "{ tags: []  inline: [{ id: \"1\" } { id: \"2\" }]  "
                        + "ix: { \"k\" => { id: \"1\" } }  pair: [\"a\" 1]  pick: \"x\" }")
                .startsWith("'tag_list' has 0 elements"));
    }
}
