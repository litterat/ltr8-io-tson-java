package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tag naming a choice's variant that is itself a choice is spent selecting it, and the value is read at the nested
 * choice untagged ([TSON-SCHEMA] §5.4, [TSON-JSON] §8.1): the same verdicts in both encodings, in tree and bind mode.
 */
class JsonNestedChoiceTest {

    private static final String ID = "https://example.test/json-nested-choice.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-nested-choice.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              narrow       => ( int32 | text )
              narrow_alias => narrow
              outer        => ( boolean | narrow )
              by_alias     => ( boolean | narrow_alias )
              h => { o?: outer  al?: by_alias  f?: ( boolean | narrow )  os?: [outer] }
            }
            """;

    private static final Tson TSON = Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA)));

    private static final Json JSON = Json.standard().withSchemas(TSON.schemaRegistry());

    private static List<Diagnostic> tson(String body) {
        return TSON.validate("!!import:\"" + ID + "\"\n!h " + body);
    }

    private static List<Diagnostic> json(String body) {
        return JSON.validate(body, ID, "h");
    }

    private static void accepted(String tsonBody, String jsonBody) {
        assertEquals(List.of(), tson(tsonBody).stream().map(Diagnostic::message).toList(), "the text encoding");
        assertEquals(List.of(), json(jsonBody).stream().map(Diagnostic::message).toList(), "the JSON encoding");
    }

    /** Both encodings refuse once, with one code at one path; the JSON refusal is returned. */
    private static Diagnostic refused(String tsonBody, String jsonBody) {
        List<Diagnostic> fromTson = tson(tsonBody);
        List<Diagnostic> fromJson = json(jsonBody);
        assertEquals(1, fromTson.size(), fromTson::toString);
        assertEquals(1, fromJson.size(), fromJson::toString);
        assertEquals(fromTson.getFirst().code(), fromJson.getFirst().code(), "the two encodings disagree");
        assertEquals(fromTson.getFirst().path(), fromJson.getFirst().path().map(path -> path.replaceAll(
                "/\\$(type|value)$", "")));
        return fromJson.getFirst();
    }

    @Test
    void aTagNamingANestedChoiceSelectsItAndTheValueReadsThereUntagged() {
        accepted("{ o: !narrow 5 }", """
                {"o": {"$type": "narrow", "$value": 5}}""");
        accepted("{ o: !narrow \"s\" }", """
                {"o": {"$type": "narrow", "$value": "s"}}""");
        accepted("{ o: !boolean true }", """
                {"o": {"$type": "boolean", "$value": true}}""");
        accepted("{ al: !narrow_alias 5  f: !narrow \"s\"  os: [ !narrow 5  !boolean false ] }", """
                {"al": {"$type": "narrow_alias", "$value": 5}, "f": {"$type": "narrow", "$value": "s"},
                 "os": [{"$type": "narrow", "$value": 5}, {"$type": "boolean", "$value": false}]}""");
    }

    @Test
    void theTreeHoldsTheValueTheNestedChoiceRead() {
        JsonValue read = JSON.treeReader().withSchema(ID).readAs("""
                {"o": {"$type": "narrow", "$value": 5}, "f": {"$type": "narrow", "$value": "s"}}""", "h");
        assertEquals(5, read.get("o").asInt());
        assertEquals("s", read.get("f").asString());
        var tree = TSON.treeReader().read("!!import:\"" + ID + "\"\n!h { o: !narrow 5  f: !narrow \"s\" }");
        assertEquals(5, tree.get("o").asInt().orElseThrow());
        assertEquals("s", tree.get("f").asString().orElseThrow());
    }

    @Test
    void aTagNamingTheNestedChoicesVariantIsRefused() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused("{ o: !int32 5 }", """
                {"o": {"$type": "int32", "$value": 5}}""").code());
    }

    @Test
    void anUntaggedValueAtTheOuterChoiceIsRefused() {
        Diagnostic refusal = refused("{ o: 5 }", """
                {"o": 5}""");
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refusal.code());
        assertEquals(java.util.Optional.of("/o"), refusal.path());
    }

    @Test
    void theValueAtTheNestedChoiceIsJudgedThere() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, refused("{ o: !narrow true }", """
                {"o": {"$type": "narrow", "$value": true}}""").code());
    }

    /** A choice of atoms binds to {@code Object}, which the context registers as an atom so the binder takes it whole. */
    public record H(Object o, Object al, Object f, List<Object> os) {
    }

    private static DataBindContext binding() {
        return DataBindContext.builder().nameBinder(DataNameBinder.ofMap(Map.of("h", H.class)))
                .registerAtoms(AtomContext.hostTypes()).registerAtom(Object.class).build();
    }

    @Test
    void aBoundNestedChoiceReceivesTheSameValueInBothEncodings() {
        H fromJson = Json.of(ProcessorConfig.defaults().withDataBindContext(binding()))
                .withSchemas(TSON.schemaRegistry()).objectReader().withSchema(ID).readAs("""
                        {"o": {"$type": "narrow", "$value": 5}, "al": {"$type": "narrow_alias", "$value": "s"},
                         "f": {"$type": "boolean", "$value": true}, "os": [{"$type": "narrow", "$value": "t"}]}""",
                        "h", H.class);
        H fromTson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA))
                .withDataBindContext(binding())).objectReader().read("!!import:\"" + ID
                + "\"\n!h { o: !narrow 5  al: !narrow_alias \"s\"  f: !boolean true  os: [ !narrow \"t\" ] }", H.class);

        assertEquals(new H(5, "s", true, List.of("t")), fromJson);
        assertEquals(fromJson, fromTson);
    }

    /** The tag names the extern, the enclosing scope's name; the foreign choice reads the value untagged (§5.13). */
    @Test
    void aTagNamingAnExternOfADisjointChoiceReadsTheValueThereInBothEncodings() {
        String foreign = "https://example.test/json-nested-choice-foreign.tn";
        String subject = "https://example.test/json-nested-choice-externs.tn";
        Map<String, String> documents = Map.of(foreign, """
                !!id:"%s"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  narrow => ( int32 | text )
                }""".formatted(foreign), subject, """
                !!id:"%s"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  narrow_x => !meta.extern { schema: "%s"  type: narrow }
                  outer    => ( boolean | narrow_x )
                  h        => { o?: outer }
                }""".formatted(subject, foreign));
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of((SchemaSource) documents::get)));

        assertEquals(List.of(), tson.validate("!!import:\"" + subject + "\"\n!h { o: !narrow_x 5 }"));
        Json json = Json.standard().withSchemas(tson.schemaRegistry());
        assertEquals(List.of(), json.validate("""
                {"o": {"$type": "narrow_x", "$value": 5}}""", subject, "h"));
        assertEquals(5, json.treeReader().withSchema(subject).readAs("""
                {"o": {"$type": "narrow_x", "$value": 5}}""", "h").get("o").asInt());
    }

    /** Refused at link, the schema never becomes the linked schema the JSON encoding compiles from. */
    @Test
    void aVariantThatIsAChoiceNotDisjointIsRefusedBeforeEitherEncodingReads() {
        String refused = """
                !!id:"https://example.test/json-nested-choice-refused.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  a      => { x: text }
                  b      => { y: text }
                  either => ( a | b )
                  h      => { o: ( text | either ) }
                }
                """;
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> JsonSchemaCompiler.compile(TSON.resolve(refused)));
        assertTrue(thrown.getMessage().contains("has a variant [either]"), thrown.getMessage());
    }
}
