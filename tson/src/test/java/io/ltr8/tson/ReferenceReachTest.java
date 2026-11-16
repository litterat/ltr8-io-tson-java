package io.ltr8.tson;

import io.ltr8.annotation.Field;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reference is a token's text, whatever its form, split at {@code .} before any family is consulted, each segment a
 * name of the family of what it enters ([TSON-SCHEMA] §5.12, [TSON-DATA] §2.4): so every field of a record value and
 * every key of a namespace value is reached by a name, and no family a segment is matched against admits {@code .}
 * ([TSON-SCHEMA] §4.2, §5.2).
 */
class ReferenceReachTest {

    private static final String ID = "https://example.test/reach.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/reach.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              word    => !unicode.identifier_type { medial: " " }
              upper   => !unicode.identifier_type { start: ASCII  continue: ASCII  normalization: ASCII_CASEFOLD }
              api     => !meta.namespace { key_type: meta.identifier  value_type: text }
              spaced  => !meta.namespace { key_type: word  value_type: text }
              verbs   => !meta.namespace { key_type: upper  value_type: text }
              worded  => !meta.record { name_type: word  fields: {}  extension: ABSTRACT }
              site    => worded & { "public api": api  internal: api }
              folded  => !meta.record { name_type: upper  fields: {}  extension: ABSTRACT }
              header  => folded & { Accept: text }
              main    => !site { "public api": !api { ping => "x" }  internal: !api { pong => "y" } }
              book    => !spaced { "get orders" => "g" }
              methods => !verbs { GET => "get" }
              h1      => !header { Accept: "json" }
              holder  => { r: meta.ref<text> }
              typed   => { t: meta.type_ref }
              held    => !holder { r: "main.public api.ping" }
              ids     => !meta.array { element_type: "text" }
            }""";

    private static Tson tson() {
        SchemaSource source = uri -> SCHEMA;
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
    }

    private static List<Diagnostic> problems(String reference) {
        return tson().validate("!!import:\"" + ID + "\"\n!holder { r: " + reference + " }");
    }

    /** A name that splits into no qualified name is no value of the reference, and one reaching nothing breaks it. */
    private static Diagnostic refused(String reference, Diagnostic.Code code) {
        List<Diagnostic> problems = problems(reference);
        assertEquals(1, problems.size(), reference + ": " + problems);
        assertEquals(code, problems.getFirst().code(), reference);
        assertEquals("/r", problems.getFirst().path().orElseThrow(), reference);
        return problems.getFirst();
    }

    /** What a reference reached, a value the schema holds bound or as a tree. */
    private static String text(ValueRef<?> reference) {
        return reference.get() instanceof TsonValue tree ? tree.asString().orElseThrow() : (String) reference.get();
    }

    private static ValueRef<?> read(String reference) {
        TsonValue tree = tson().treeReader().withSchema(ID).read("!!import:\"" + ID + "\"\n!holder { r: " + reference
                + " }");
        return tree.get("r").as(ValueRef.class).orElseThrow(() -> new AssertionError("not a reference: " + tree));
    }

    @Test
    void aRecordFieldNamedWithASpaceIsReached() {
        assertEquals(List.of(), problems("main.internal.pong"));
        assertEquals(List.of(), problems("\"main.public api.ping\""));
        assertEquals("x", text(read("\"main.public api.ping\"")));
    }

    @Test
    void aKeyOfANamespaceKeyedByASpacedFamilyIsReached() {
        assertEquals("g", text(read("\"book.get orders\"")));
    }

    @Test
    void aQuotedReferenceIsItsUnquotedSpelling() {
        assertEquals(read("main.internal.pong"), read("\"main.internal.pong\""));
        assertEquals("y", text(read("\"main.internal.pong\"")));
    }

    /** A segment is matched as a document's own key or field name there is, in the family's form. */
    @Test
    void aSegmentIsMatchedInItsFamilysForm() {
        assertEquals("get", text(read("methods.get")));
        assertEquals("json", text(read("h1.accept")));
    }

    @Test
    void anEmptySegmentIsRefusedInEitherForm() {
        for (String empty : List.of("\"main..internal.pong\"", "\".main.internal.pong\"", "\"main.internal.pong.\"",
                "\"\"")) {
            refused(empty, Diagnostic.Code.ATOM_FORM_INVALID);
        }
        String message = refused("\"main..internal.pong\"", Diagnostic.Code.ATOM_FORM_INVALID).message();
        assertTrue(message.contains("empty segment"), message);
    }

    /** {@code main."public api".ping} is three tokens, as it always was. */
    @Test
    void aQuotedSegmentIsNoSpelling() {
        assertFalse(problems("main.\"public api\".ping").isEmpty());
    }

    @Test
    void aSegmentThatIsNoNameOfItsFamilyIsRefusedByThatFamily() {
        String message = refused("\"main.internal.po ng\"", Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION).message();
        assertTrue(message.contains("'po ng' is no name of 'meta.identifier', which names the keys of "
                + "'main.internal'"), message);
        message = refused("\"main.public  api.ping\"", Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION).message();
        assertTrue(message.contains("is no name of 'word', which names the fields of 'main'"), message);
        message = refused("\"ma in.internal.pong\"", Diagnostic.Code.ATOM_FORM_INVALID).message();
        assertTrue(message.contains("'ma in' is no name"), message);
    }

    @Test
    void aSegmentNamingNoKeyListsTheKeys() {
        String message = refused("\"main.public api.pong\"", Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION).message();
        assertTrue(message.contains("has no key 'pong' -- it holds ping"), message);
    }

    /**
     * A value entry's body and a constructor's are read as data, and a reference there reaches what a data
     * document's does; a field's default at a reference is a value too.
     */
    @Test
    void aSchemaDocumentsBodyReadsTheQuotedForm() {
        assertEquals(List.of(), tson().validateSchema(SCHEMA));
        String defaulted = SCHEMA.replace("  holder  =>",
                "  picked  => { r?: meta.ref<text> ~ \"main.public api.ping\"  t?: meta.type_ref ~ \"text\" }\n"
                        + "  holder  =>");
        assertEquals(List.of(), tson().validateSchema(defaulted));
    }

    public record Holder(ValueRef<Object> r) {
    }

    public record Typed(TypeRef t) {
    }

    public record Site(@Field("public api") Map<String, String> publicApi, Map<String, String> internal) {
    }

    public record Header(@Field("Accept") String accept) {
    }

    private static <T> T bind(Class<T> host, String data) {
        SchemaSource source = uri -> SCHEMA;
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("holder", Holder.class, "typed", Typed.class,
                                        "site", Site.class, "header", Header.class))
                                .orElse(SchemaModelNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).build()));
        return tson.objectReader().read("!!import:\"" + ID + "\"\n" + data, host);
    }

    @Test
    void aBoundReadTakesEitherForm() {
        Holder holder = bind(Holder.class, "!holder { r: \"main.public api.ping\" }");
        assertEquals("main.public api.ping", holder.r().name());
        assertEquals("x", holder.r().get());
        assertEquals(TypeRef.of("text"), bind(Typed.class, "!typed { t: \"text\" }").t());
    }

    @Test
    void aReferenceIsWrittenQuotedExactlyWhereASegmentNeedsIt() {
        Tson tson = tson();
        for (String reference : List.of("\"main.public api.ping\"", "main.internal.pong")) {
            TsonValue tree = tson.treeReader().withSchema(ID).read("!!import:\"" + ID + "\"\n!holder { r: "
                    + reference + " }");
            String written = tson.treeWriter().describing(ID).toTson(tree);
            assertTrue(written.contains("r: " + reference), written);
            assertEquals(List.of(), tson.validate(written), written);
        }
    }

    // ── No family a segment is matched against admits '.' ─────────────────────────────────────────────

    private static final String HEADER = """
            !!id:"https://example.test/dotted-%s.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/net.tn"
            !schema {
            %s
            }""";

    private static void loads(String id, String body) {
        Tson.standard().resolve(HEADER.formatted(id, body));
    }

    private static String refusedSchema(String id, String body) {
        return assertThrows(SchemaValidationException.class, () -> loads(id, body)).getMessage();
    }

    /** A type position the schema grammar reads names a type, every segment entering a schema map: never quoted. */
    @Test
    void aTypePositionOfTheSchemaGrammarRefusesAQuotedToken() {
        for (String body : List.of("r => { b: \"text\" }", "box => <T> { v: T }\nb => box<\"text\">",
                "a => \"text\"", "base => { a: text }\nr => \"base\" & { b: text }")) {
            assertFalse(Tson.standard().validateSchema(HEADER.formatted("grammar", body)).isEmpty(), body);
        }
    }

    @Test
    void aNameTypeAdmittingADotIsRefusedWhereItIsNamed() {
        Map<String, String> families = Map.of(
                "start-add", "!unicode.identifier_type { start_add: \".\" }",
                "continue-add", "!unicode.identifier_type { continue_add: \".\" }",
                "medial", "!unicode.identifier_type { medial: \".\" }");
        families.forEach((id, family) -> {
            String message = refusedSchema("record-" + id, "dotted => " + family + "\n"
                    + "r => !meta.record { name_type: dotted  fields: { a => { type: text } } }");
            assertTrue(message.contains("'r'") && message.contains("admits '.'"), message);
            message = refusedSchema("namespace-" + id, "dotted => " + family + "\n"
                    + "n => !meta.namespace { key_type: dotted  value_type: text }");
            assertTrue(message.contains("'n' key_type names 'dotted'") && message.contains("admits '.'"), message);
        });
    }

    @Test
    void aNameTypeTakenFromASupertypeIsJudgedToo() {
        String message = refusedSchema("supertype", """
                dotted => !unicode.identifier_type { continue_add: "." }
                base   => !meta.record { name_type: dotted  fields: {}  extension: ABSTRACT }
                r      => base & { a: text }""");
        assertTrue(message.contains("admits '.'"), message);
    }

    @Test
    void aDotTheProfileExcludesIsNoDot() {
        loads("excluded", """
                undotted => !unicode.identifier_type { continue_add: ".-"  exclude: "." }
                r => !meta.record { name_type: undotted  fields: { a => { type: text } } }
                n => !meta.namespace { key_type: undotted  value_type: text }""");
    }

    /** A dotted family is untouched where no segment is matched: a plain map's key, an enum's name type. */
    @Test
    void aDottedFamilyStillTypesWhatNoSegmentEnters() {
        loads("scope", """
                dotted => !unicode.identifier_type { continue_add: "." }
                m      => { dotted => text }
                e      => !meta.enum_type { name_type: dotted  members: [a.b c] }
                s      => { scheme: net.scheme_name }""");
    }
}
