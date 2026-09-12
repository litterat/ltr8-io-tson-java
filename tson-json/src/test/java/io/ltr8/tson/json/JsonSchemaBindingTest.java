package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchemaLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-JSON] §3.4's out-of-band route through the front door: the application supplies the schema and the
 * root type, and the document is a bare value read directly at that type.
 */
class JsonSchemaBindingTest {

    private static final String ID = "https://example.test/binding-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/binding-1.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              person => { name: text  tries: int32 ~ 0 }
              alias  => person
            }
            """;

    /**
     * The whole of the wiring an application does: resolve the schema once through the TSON engine, whose
     * job that is, and hand its registry over. Nothing here names a {@code tson-compiler} type.
     */
    private static Json json() {
        Tson tson = Tson.standard();
        TsonLinkedSchema linked = tson.resolve(SCHEMA);
        assertEquals(ID, linked.schema().id());
        return Json.standard().withSchemas(tson.schemaRegistry());
    }

    @Test
    void aConformingDocumentValidatesAndComesBackAsTheJsonItWas() {
        assertEquals(List.of(), json().validate("""
                {"name": "Ada", "tries": 1}""", ID, "person"));
    }

    /** §6.1.3: a missing REQUIRED_DEFAULT member injects, so decoded output is fully populated. */
    @Test
    void theTreeComesBackWithItsDefaultsInjected() {
        JsonValue read = json().treeReader().withSchema(ID).readAs("""
                {"name": "Ada"}""", "person");
        assertEquals("""
                {"name":"Ada","tries":0}""", read.toString());
    }

    @Test
    void aNonConformingDocumentIsReportedRatherThanThrown() {
        List<Diagnostic> problems = json().validate("""
                {"tries": 1}""", ID, "person");
        assertEquals(1, problems.size(), () -> String.valueOf(problems));
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, problems.getFirst().code());
        assertEquals("/name", problems.getFirst().path().orElseThrow());
    }

    /** A document that will not even parse is a diagnostic too: a collecting read throws for nothing. */
    @Test
    void aDocumentThatWillNotParseIsReportedThroughTheSameChannel() {
        List<Diagnostic> problems = json().validate("{\"name\": ", ID, "person");
        assertFalse(problems.isEmpty(), "a syntax failure reaches the receiver");
        assertTrue(problems.getFirst().code().verdict(), "and it is a verdict the sender can act on");
    }

    /** A fail-fast read still throws, because its receiver is what throws. */
    @Test
    void aFailFastReadStillRaises() {
        assertThrows(ReadException.class, () -> json().treeReader().withSchema(ID).readAs("""
                {"tries": 1}""", "person"));
    }

    // ── Reaching the schema, which is not a verdict on the document ──────

    /** A schema nothing supplies is `SCHEMA_NOT_FOUND`, and `verdict()` is false: nobody judged the document. */
    @Test
    void aSchemaNothingSuppliesIsNotAVerdict() {
        List<Diagnostic> problems = json().validate("{}", "https://example.test/absent-1.tn", "person");
        assertEquals(Diagnostic.Code.SCHEMA_NOT_FOUND, problems.getFirst().code());
        assertFalse(problems.getFirst().code().verdict());
    }

    /**
     * A root type the schema does not declare is {@code UNKNOWN_TYPE}, naming what it does declare -- the
     * same code {@code TsonTreeReader.readAs} gives the same mistake, which is what parity requires here.
     *
     * <p>Unlike the schema-fetch codes it <em>is</em> a verdict, and that is a question about both encodings
     * rather than this one: out of band the root type is the application's to supply, so a name that does
     * not exist is arguably its misconfiguration and not the document's fault. The CLI does not wait for
     * that argument to be settled -- it checks the name up front and calls a bad {@code --type} a usage
     * error, which is where the person who typed it can act.
     */
    @Test
    void anUndeclaredRootTypeNamesWhatIsDeclared() {
        List<Diagnostic> problems = json().validate("{}", ID, "persno");
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problems.getFirst().code());
        assertTrue(problems.getFirst().expected().contains("person"), problems.getFirst().expected());
    }

    @Test
    void aFailedBindingHandsBackNoTree() {
        assertNull(Json.standard().withSchemas(uri -> Optional.<TsonLinkedSchema>empty())
                .treeReader().withDiagnostics(d -> { }).withSchema(ID).readAs("{}", "person"));
    }

    // ── The two doors are separate ──────────────────────────────────────

    /** §3.1: the pull past the root value is what rejects trailing content, schema or no schema. */
    @Test
    void trailingContentIsRejectedUnderASchemaToo() {
        assertFalse(json().validate("""
                {"name": "Ada"} 2""", ID, "person").isEmpty());
    }

    /** A reader that can name no schema says so, rather than reading a document against nothing. */
    @Test
    void aSchemalessReaderRefusesToNameASchema() {
        assertThrows(IllegalStateException.class, () -> Json.standard().treeReader().withSchema(ID));
        assertThrows(IllegalStateException.class,
                () -> json().treeReader().readAs("{}", "person"));
    }

    /** An alias reads as its target -- [TSON-SCHEMA] §8.3's collapse, reached through the front door. */
    @Test
    void anAliasIsAUsableRootType() {
        assertEquals(List.of(), json().validate("""
                {"name": "Ada"}""", ID, "alias"));
    }

    /** The schemaless door is unchanged: `read` consults nothing and `null` is a value there. */
    @Test
    void theSchemalessDoorIsUntouched() {
        assertEquals("""
                {"a":null}""", json().treeReader().read("""
                {"a": null}""").toString());
    }
}
