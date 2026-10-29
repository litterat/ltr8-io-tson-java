package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-JSON] §8.5's declared positions, the open sum over the governing closure, read end to end: the value
 * names its own type in an annotation object led by {@code $type}, and a value naming no type is refused in every
 * mode. {@code DeclaredReadTest} is the TSON text peer.
 */
class JsonDeclaredReadTest {

    private static final String HOST = "https://example.test/json-declared-host.tn";

    private static final String HOST_SCHEMA = """
            !!id:"https://example.test/json-declared-host.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              note     => { body: text }
              memo     => { body: text  urgent: boolean }
              count    => !integer ^ { min: 0  max: 9 }
              envelope => { local: declared }
              inbox    => { items: [declared] }
            }
            """;

    private static final Tson TSON = registered();

    private static Tson registered() {
        Tson tson = Tson.standard();
        tson.resolve(HOST_SCHEMA);
        return tson;
    }

    private static final Json JSON = Json.standard().withSchemas(TSON.schemaRegistry());

    private static JsonValue read(String rootType, String json) {
        return JSON.treeReader().withSchema(HOST).readAs(json, rootType);
    }

    private static List<Diagnostic> problems(String rootType, String json) {
        return JSON.validate(json, HOST, rootType);
    }

    private static Diagnostic refusal(String rootType, String json) {
        List<Diagnostic> problems = problems(rootType, json);
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }

    private static String envelope(String local) {
        return "{\"local\": " + local + "}";
    }

    /** `declared` takes any type the governing namespace holds; the choice of which is the data's. */
    @Test
    void aDeclaredPositionTakesAnyTypeTheGoverningSchemaDeclares() {
        for (String local : List.of("""
                {"$type": "note", "body": "hi"}""", """
                {"$type": "memo", "body": "hi", "urgent": true}""")) {
            JsonValue value = read("envelope", envelope(local));
            assertTrue(value.toString().startsWith("{\"local\":{\"body\":\"hi\""), value::toString);
        }
    }

    /** §3.3's wrapper carries a value of any shape -- an atom from the governing namespace, here. */
    @Test
    void anAtomRidesInTheWrapperForm() {
        assertEquals(List.of(), problems("envelope", envelope("""
                {"$type": "count", "$value": 7}""")));
        assertEquals(List.of(), problems("envelope", envelope("""
                {"$type": "int32", "$value": -1}""")));
    }

    /** The selected type validates in full: its facets hold inside the wrapper. */
    @Test
    void theSelectedTypeValidatesInFull() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "count", "$value": 10}"""));
        assertTrue(refusal.path().orElseThrow().startsWith("/local"), refusal::toString);
    }

    /** A name the governing namespace does not hold is an unresolved reference. */
    @Test
    void aDeclaredPositionRefusesANameTheGoverningSchemaDoesNotHold() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$type": "claim", "id": "a", "amount": 1}"""));
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, refusal.code());
        assertEquals("/local", refusal.path().orElseThrow());
    }

    /** §8.5: a bare value at a declared position is a validation error -- there is no permissive reading. */
    @Test
    void aBareValueIsRefusedInEveryShape() {
        for (String bare : List.of("\"hi\"", "3", "true", "[1, 2]", "{\"body\": \"hi\"}")) {
            Diagnostic refusal = refusal("envelope", envelope(bare));
            assertEquals(Diagnostic.Code.VALIDATION_ERROR, refusal.code(), bare);
            assertEquals("/local", refusal.path().orElseThrow(), bare);
        }
    }

    /** An annotation object without `$type` names no type either. */
    @Test
    void aWrapperWithNoTypeIsRefused() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$value": 1}"""));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, refusal.code());
    }

    /** Each element of {@code [declared]} names its own type. */
    @Test
    void eachArrayElementNamesItsOwnType() {
        assertEquals(List.of(), problems("inbox", """
                {"items": [{"$type": "note", "body": "a"}, {"$type": "memo", "body": "b", "urgent": false}]}"""));
    }

    /**
     * §3.3, §8.5: {@code $import} stands at the top of the document alone, so one at a declared position is a
     * resolver error, as at every other value position -- a value cannot widen the namespace it is read in.
     */
    @Test
    void anImportMemberAtADeclaredPositionIsRefused() {
        Diagnostic refusal = refusal("envelope", envelope("""
                {"$import": ["https://example.test/other.tn"], "$type": "note", "body": "hi"}"""));
        assertEquals(Diagnostic.Code.SCOPE_NOT_ADMITTED, refusal.code());
        assertEquals("/local/$import", refusal.path().orElseThrow());
    }

    public record Note(String body) {
    }

    public record Envelope(Note local) {
    }

    private static Json bound() {
        DataBindContext binding = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("envelope", Envelope.class, "note", Note.class)))
                .registerAtoms(AtomContext.hostTypes()).build();
        return Json.of(ProcessorConfig.defaults().withDataBindContext(binding)).withSchemas(TSON.schemaRegistry());
    }

    /** A bind read hands back the object the selected type built. */
    @Test
    void bindModeReadsTheValueIntoTheClassItsTypeNames() {
        List<Diagnostic> problems = new ArrayList<>();
        Envelope read = bound().objectReader().withDiagnostics(problems::add).withSchema(HOST).readAs("""
                {"local": {"$type": "note", "body": "hi"}}""", "envelope", Envelope.class);
        assertEquals(List.of(), problems);
        assertEquals(new Envelope(new Note("hi")), read);
    }

    /** A bind read refuses a value naming no type exactly as a tree read does. */
    @Test
    void bindModeRefusesAnUntypedValue() {
        List<Diagnostic> problems = new ArrayList<>();
        Envelope read = bound().objectReader().withDiagnostics(problems::add).withSchema(HOST).readAs("""
                {"local": {"body": "hi"}}""", "envelope", Envelope.class);
        assertNull(read);
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
    }
}
