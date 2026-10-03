package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code text_type}'s {@code normalization} end to end (SPEC-FEEDBACK.md #19): a value is its token's text put
 * into the form, and everything downstream judges the value -- the facets, a pin, map-key identity, the output.
 * {@code NFKC_CASEFOLD} makes the names of a case-insensitive naming system one value however they are cased.
 */
class TextNormalizationTest {

    private static final String ID = "https://example.test/normalized-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/normalized-1.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              header_name => !identifier_type { start: NONE  continue: NONE
                start_add: "abcdefghijklmnopqrstuvwxyz"  continue_add: "abcdefghijklmnopqrstuvwxyz0123456789-"
                normalization: NFKC_CASEFOLD }
              host => !text_type { pattern: "[a-z0-9.-]+"  normalization: NFKC_CASEFOLD }
              charset => !text_type { members: [UTF-8 us-ascii]  normalization: NFKC_CASEFOLD }
              name => !identifier_type { continue_add: "-" }
              request => { headers?: {header_name => text}  host?: host  charset?: charset  label?: name }
              idempotent => { header: header_name = Idempotency-Key }
            }
            """;

    private static Tson tson() {
        SchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (base.equals(ID)) {
                return SCHEMA;
            }
            throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND,
                    "this fixture serves only " + ID, null);
        };
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
    }

    private static String document(String body) {
        return "!!schema:\"" + ID + "\"\n" + body;
    }

    private static List<Diagnostic.Code> codes(String body) {
        return tson().validate(document(body)).stream().map(Diagnostic::code).toList();
    }

    @Test
    void theSchemaLoads() {
        assertEquals(List.of(), tson().validateSchema(SCHEMA));
    }

    @Test
    void aFoldedValueIsWhatTheTreeHolds() {
        TsonValue tree = tson().treeReader().read(document("!request { host: Example.COM  charset: utf-8 }"));
        assertEquals("example.com", tree.get("host").asString().orElseThrow());
        assertEquals("utf-8", tree.get("charset").asString().orElseThrow());
    }

    /** The pattern and the member set judge the value, so a spelling neither admits as written is admitted. */
    @Test
    void theFacetsJudgeTheValueNotTheSpelling() {
        assertEquals(List.of(), codes("!request { host: Example.COM  charset: Us-Ascii }"));
        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION), codes("!request { charset: latin1 }"));
    }

    @Test
    void twoSpellingsOfOneNameAreOneMapKey() {
        assertEquals(List.of(), codes("!request { headers: { Content-Type => a  Accept => b } }"));
        assertEquals(List.of(Diagnostic.Code.DUPLICATE_MAP_KEY),
                codes("!request { headers: { Content-Type => a  content-type => b } }"));
    }

    /** NFKC folds a full-width spelling to ASCII before the profile judges it, so it is the same name. */
    @Test
    void aFullWidthSpellingIsTheSameName() {
        assertEquals(List.of(Diagnostic.Code.DUPLICATE_MAP_KEY), codes(
                "!request { headers: { content-type => a  \"Ｃontent-Ｔype\" => b } }"));
    }

    @Test
    void aPinComparesValues() {
        assertEquals(List.of(), codes("!idempotent { header: idempotency-key }"));
        assertEquals(List.of(), codes("!idempotent { header: IDEMPOTENCY-KEY }"));
        assertEquals(List.of(Diagnostic.Code.FIELD_FIXED), codes("!idempotent { header: request-id }"));
    }

    @Test
    void theWriterEmitsTheValue() {
        Tson tson = tson();
        TsonValue tree = tson.treeReader().read(document("!request { headers: { Content-Type => a } }"));
        String written = tson.treeWriter().describing(ID).toTson(tree);
        assertTrue(written.contains("content-type"), written);
    }

    /** An identifier's form is NFC by default, and a quoted decomposed spelling reads as its composed value. */
    @Test
    void anIdentifierIsNfcNormalisedRatherThanRefused() {
        TsonValue tree = tson().treeReader().read(document("!request { label: \"café\" }"));
        assertEquals("café", tree.get("label").asString().orElseThrow());
    }

    @Test
    void aRefinementCannotSetANormalization() {
        List<Diagnostic> refused = tson().validateSchema(SCHEMA.replace(
                "name => !identifier_type { continue_add: \"-\" }",
                "name => !identifier_type { continue_add: \"-\" }\n  folded => !text ^ { normalization: NFKC_CASEFOLD }"));
        assertEquals(1, refused.size(), refused.toString());
        assertTrue(refused.getFirst().message().contains("fixed where it is constructed"), refused.toString());
    }

    @Test
    void aRegexIsAlwaysItsTextAsWritten() {
        List<Diagnostic> refused = tson().validateSchema(SCHEMA.replace(
                "name => !identifier_type { continue_add: \"-\" }",
                "name => !identifier_type { continue_add: \"-\" }\n  folded => !regex_type { normalization: NFKC }"));
        assertEquals(1, refused.size(), refused.toString());
    }

    @Test
    void twoMembersThatAreOneValueAreRefused() {
        List<Diagnostic> refused = tson().validateSchema(SCHEMA.replace("members: [UTF-8 us-ascii]",
                "members: [UTF-8 utf-8]"));
        assertEquals(1, refused.size(), refused.toString());
        assertTrue(refused.getFirst().message().contains("are one value under NFKC_CASEFOLD"), refused.toString());
    }
}
