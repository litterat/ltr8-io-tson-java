package io.ltr8.tson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.tson.base.Diagnostic;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A record's field names have a type (SPEC-FEEDBACK.md #10). The grammar admits any single-line token in name
 * position; a record judges its names by its field name type, an identifier unless it says otherwise.
 */
class FieldNameTypeTest {

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst();
    }

    /**
     * A schemaless record's field names are identifiers, and with no declared fields to be unknown against, one
     * that is no identifier is a resolver error, at its own path.
     */
    @Test
    void aSchemalessFieldNameThatIsNoIdentifierIsAResolverError() {
        Diagnostic problem = only(Tson.standard().validate("{ \"first name\": 1 }"));
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, problem.code());
        assertEquals("/first name", problem.path().orElseThrow());
        assertTrue(problem.message().contains("belongs in a map"), problem.message());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, only(Tson.standard().validate("{ \"@id\": 1 }")).code());
    }

    @Test
    void aQuotedIdentifierIsStillAFieldName() {
        assertEquals(List.of(), Tson.standard().validate("{ \"order-id\": 1  \"true\": 2 }"));
    }

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            """;

    private static String schema(String id, String body) {
        return HEADER.formatted(id) + "{\n" + body + "\n}\n";
    }

    /** A record states its field names' family; a JSON-LD translation's admits a leading @ or $. */
    static final String JSON_LD = schema("json-ld", """
              json_name => !identifier_type { start_add: "@$" }
              node => !record {
                name_type: json_name
                fields: [
                  { name: "@id"  type: text }
                  { name: "$ref"  type: text  optional: true }
                  { name: label  type: text }
                ]
              }
            """);

    @Test
    void aRecordMayStateARelaxedFieldNameType() {
        Tson tson = Tson.standard();
        tson.resolve(JSON_LD);
    }

    private static List<Diagnostic> read(String schemaText, String id, String body) {
        Tson tson = Tson.standard();
        tson.resolve(schemaText);
        return tson.validate("!!schema:\"https://example.test/" + id + ".tn\"\n" + body);
    }

    /** A document's field names are judged by the record that reads them, not by the identifier default. */
    @Test
    void aDocumentsFieldNamesAreReadByTheirRecordsType() {
        assertEquals(List.of(), read(JSON_LD, "json-ld", "!node { \"@id\": \"a\"  label: \"b\" }"));
        assertEquals(List.of(), read(JSON_LD, "json-ld", "!node { \"@id\": \"a\"  \"$ref\": \"r\"  label: \"b\" }"));
    }

    /**
     * A name of the type the record does not declare is unrecognised, as any other is; one outside the type can be
     * no field either, and is unrecognised at its own path with the type's reason.
     */
    @Test
    void aNameOutsideTheRecordsTypeIsUnknownWithTheTypesReason() {
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, only(read(JSON_LD, "json-ld",
                "!node { \"@id\": \"a\"  \"@type\": \"t\"  label: \"b\" }")).code());
        Diagnostic refused = only(read(JSON_LD, "json-ld", "!node { \"@id\": \"a\"  \"a b\": 1  label: \"b\" }"));
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, refused.code());
        assertEquals("/a b", refused.path().orElseThrow());
        assertTrue(refused.message().contains("not a value of the record's name type 'json_name'"), refused.message());
    }

    /** The rule is the reading record's: a record nested in a relaxed one keeps its own identifier names. */
    @Test
    void aNestedRecordJudgesItsOwnNames() {
        String nested = schema("nested", """
                  json_name => !identifier_type { start_add: "@$" }
                  plain => { a: text }
                  holder => !record { name_type: json_name  fields: [
                    { name: "@id"  type: text }  { name: inner  type: plain } ] }
                """);
        assertEquals(List.of(), read(nested, "nested", "!holder { \"@id\": \"x\"  inner: { a: \"y\" } }"));
        List<Diagnostic> problems = read(nested, "nested", "!holder { \"@id\": \"x\"  inner: { \"@a\": \"y\" } }");
        assertEquals(List.of(Diagnostic.Code.UNRECOGNIZED_FIELD, Diagnostic.Code.FIELD_REQUIRED),
                problems.stream().map(Diagnostic::code).toList(), problems.toString());
        assertEquals("/inner/@a", problems.getFirst().path().orElseThrow());
        assertTrue(problems.getFirst().message().startsWith("unknown field '@a' on 'plain' -- a record is closed"),
                problems.getFirst().message());
    }

    /**
     * A family's discriminator scan looks ahead across the member's names before any member reads them; a name is
     * judged when the member that reads it does, by that member's type, and once.
     */
    @Test
    void aDiscriminatorScanLeavesTheNamesItCrossesToTheMember() {
        String family = schema("family", """
                  json_name => !identifier_type { start_add: "@$" }
                  node => !record { name_type: json_name  fields: [ { name: "@id"  type: text } ] }
                  pet => abstract node & { "@type": text =?  name: text }
                  dog => pet & { "@type"?: = "dog"  breed: text }
                  cat => pet & { "@type"?: = "cat"  indoor: boolean }
                  holder => { p: pet }
                """);
        assertEquals(List.of(), read(family, "family",
                "!holder { p: { \"@id\": \"x\"  \"@type\": \"dog\"  name: \"Rex\"  breed: \"corgi\" } }"));
        List<Diagnostic> problems = read(family, "family",
                "!holder { p: { \"@id\": \"x\"  \"a b\": 1  \"@type\": \"dog\"  name: \"Rex\"  breed: \"c\" } }");
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, only(problems).code());
    }

    /** A JSON-LD node bound to a Java record, whose components name the members they hold. */
    public record Node(@io.ltr8.annotation.Field("@id") String id,
                       @io.ltr8.annotation.Field("$ref") String ref, String label) {
    }

    /** A name that is no identifier binds by the name the component states, as any other does. */
    private static Tson boundJsonLd() {
        io.ltr8.tson.base.source.SchemaSource source = uri -> JSON_LD;
        io.ltr8.bind.DataBindContext context = io.ltr8.bind.DataBindContext.builder()
                .nameBinder(io.ltr8.bind.DataNameBinder.ofMap(java.util.Map.of("node", Node.class))
                        .orElse(io.ltr8.tson.compiler.config.SchemaMetaNameBinder.INSTANCE))
                .build();
        Tson tson = Tson.of(io.ltr8.tson.base.ProcessorConfig.defaults()
                .withSchemaAccess(io.ltr8.tson.base.source.SchemaAccess.of(source))
                .withDataBindContext(context));
        tson.resolve(JSON_LD);
        return tson;
    }

    @Test
    void aRelaxedNameBindsThroughTheComponentThatStatesIt() {
        Tson tson = boundJsonLd();
        Node read = tson.objectReader().read("""
                !!schema:"https://example.test/json-ld.tn"
                !node { "@id": "urn:x"  "$ref": "#/a"  label: "x" }""", Node.class);
        assertEquals(new Node("urn:x", "#/a", "x"), read);
        assertEquals(new Node("urn:x", null, "x"), tson.objectReader().read("""
                !!schema:"https://example.test/json-ld.tn"
                !node { "@id": "urn:x"  label: "x" }""", Node.class));
    }

    /** Written, a name that is no identifier is quoted, so the document reads back to the same value. */
    @Test
    void aRelaxedNameIsWrittenQuoted() {
        Tson tson = boundJsonLd();
        String written = tson.objectWriter().describing("https://example.test/json-ld.tn", "node")
                .toTson(new Node("urn:x", "#/a", "x"));
        assertTrue(written.contains("\"@id\": \"urn:x\""), written);
        assertTrue(written.contains("label: \"x\""), written);
        assertEquals(new Node("urn:x", "#/a", "x"), tson.objectReader().read(written, Node.class));
    }

    /**
     * An identifier type whose profile admits a single space between two characters makes words field names, judged
     * as names under that profile: a doubled or trailing space is no name of it.
     */
    @Test
    void aWordsNameTypeAdmitsSpacedNamesAndJudgesThemAsNames() {
        String words = schema("words", """
              words => !identifier_type { continue_add: "-"  medial: " " }
              row => !record { name_type: words  fields: [ { name: "first name"  type: text } ] }
            """);
        assertEquals(List.of(), read(words, "words", "!row { \"first name\": \"Ada\" }"));
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, read(words, "words",
                "!row { \"first name\": \"Ada\"  \"last  name\": \"L\" }").getFirst().code());
    }

    /** A record's field names are names, so a name type that is no identifier family is refused at schema load. */
    @Test
    void aTextNameTypeIsRefused() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("texts", """
                      row => !record { name_type: text  fields: [ { name: "first name"  type: text } ] }
                    """)));
        assertTrue(thrown.getMessage().contains("its name_type 'text' is not an identifier family"),
                thrown.getMessage());
    }

    /**
     * A name type's form implies NFC, for a record's names and an enum's alike: under {@code NONE}, two canonically
     * equivalent spellings would be two names. {@code ASCII_CASEFOLD} implies NFC only of ASCII, so it is admitted
     * for a type whose every character is ASCII and refused for one whose profile reaches beyond.
     */
    @Test
    void aNameTypesFormMustImplyNfc() {
        for (String body : new String[] {
                "raw => !identifier_type { normalization: NONE }\n"
                        + "row => !record { name_type: raw  fields: [ { name: a  type: text } ] }",
                "raw => !identifier_type { normalization: NONE }\nkinds => !enum_type { name_type: raw  members: [a] }",
                "folded => !identifier_type { normalization: ASCII_CASEFOLD }\n"
                        + "row => !record { name_type: folded  fields: [ { name: a  type: text } ] }"}) {
            var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    io.ltr8.tson.base.SchemaValidationException.class,
                    () -> Tson.standard().resolve(schema("forms", body)), body);
            assertTrue(thrown.getMessage().contains("cannot judge names") && thrown.getMessage().contains("NFC"),
                    thrown.getMessage());
        }
        Tson.standard().resolve(schema("ascii", """
              header => !identifier_type { start: NONE  continue: NONE  normalization: ASCII_CASEFOLD
                start_add: "abcdefghijklmnopqrstuvwxyz"  continue_add: "abcdefghijklmnopqrstuvwxyz0123456789"
                medial: "-" }
              headers => !record { name_type: header  fields: [ { name: "content-type"  type: text } ] }
            """));
    }

    /**
     * The {@code ASCII} base makes a case-insensitive ASCII name type one line, an HTTP field name's: admitted under
     * {@code ASCII_CASEFOLD}, and a document's {@code Content-Type} is the field {@code content-type}.
     */
    @Test
    void anAsciiBaseMakesAnHttpFieldNameOneLine() {
        String headers = schema("headers", """
              header => !identifier_type { start: ASCII  continue: ASCII  medial: "-"  normalization: ASCII_CASEFOLD }
              headers => !record { name_type: header  fields: [ { name: "content-type"  type: text } ] }
            """);
        assertEquals(List.of(), read(headers, "headers", "!headers { \"Content-Type\": \"text/plain\" }"));
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, only(read(headers, "headers",
                "!headers { \"content-type\": \"a\"  \"x-\u00e9\": 1 }")).code());
    }

    /** The default does not change under a schema either: an identifier, refused at schema load. */
    @Test
    void aNameOutsideTheRecordsTypeIsRefusedAtSchemaLoad() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("default", """
                      node => !record { fields: [ { name: "@id"  type: text } ] }
                    """)));
        assertTrue(thrown.getMessage().contains("field name '@id' is not an identifier"), thrown.getMessage());
        org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("number", """
                      node => !record { name_type: int32  fields: [ { name: "1"  type: text } ] }
                    """)));
    }

    /** A composition absorbs its supertypes' names, so it takes their name_type; a refinement keeps its own. */
    @Test
    void aCompositionAndARefinementInheritTheFieldNameType() {
        var schema = Tson.standard().resolve(schema("inherit", """
              json_name => !identifier_type { start_add: "@$" }
              node => !record { name_type: json_name  fields: [
                { name: "@id"  type: text }  { name: "$ref"  type: text  optional: true } ] }
              labelled => node & { label: text }
              tightened => node ^ { "$ref": text }
            """));
        assertEquals("json_name", ((io.ltr8.tson.schema.meta.RecordBody) schema.schema().entries().get("labelled").body())
                .nameType());
        assertEquals("json_name", ((io.ltr8.tson.schema.meta.RecordBody) schema.schema().entries().get("tightened").body())
                .nameType());
    }

    /**
     * A translation states its name type once, on a fieldless abstract base, and its records take it by composition;
     * the base's family asks nothing of a position typed by a member, which reads untagged.
     */
    @Test
    void aFieldlessBaseGivesItsCompositionsTheirFieldNameType() {
        String mixin = schema("mixin", """
                  json_name => !identifier_type { start_add: "@$" }
                  json_record => !record { name_type: json_name  extension: ABSTRACT  fields: [] }
                  node => json_record & { "@id": text  label: text }
                  holder => { n: node }
                """);
        assertEquals(List.of(), read(mixin, "mixin", "!holder { n: { \"@id\": \"x\"  label: \"y\" } }"));
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, only(read(mixin, "mixin",
                "!node { \"@id\": \"x\"  \"a b\": 1  label: \"y\" }")).code());
    }

    /**
     * A template's held body is text, and a declared name that is no identifier is written quoted in it, so a
     * template composing a translation's base, a plain template declaring one, and an explicit one over a words
     * type all hold and close; a plain one applied gives the identifier verdict its hand-written record would.
     */
    @Test
    void aTemplateHoldsAndClosesNamesThatAreNoIdentifiers() {
        String templates = schema("templates", """
                  json_name => !identifier_type { start_add: "@" }
                  json_record => !record { name_type: json_name  extension: ABSTRACT  fields: [] }
                  page => <T> json_record & { "@id": T  "@type"?: text }
                  p => page<text>
                  words => !identifier_type { continue_add: "-"  medial: " " }
                  row => <T> !record { name_type: words  fields: [ { name: "first name"  type: T } ] }
                  r => row<text>
                """);
        assertEquals(List.of(), read(templates, "templates", "!p { \"@id\": \"x\" }"));
        assertEquals(List.of(), read(templates, "templates", "!r { \"first name\": \"Ada\" }"));
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("plain-template", """
                      box => <T> { "@id": T }
                      b => box<text>
                    """)));
        assertTrue(thrown.getMessage().contains("field name '@id' is not an identifier"), thrown.getMessage());
    }

    /**
     * The name type's inheritance is the record constructor's, not the {@code &} sugar's: the explicit
     * {@code !record { supertypes: [...] }} takes its supertypes' type when it states none and is refused when it
     * states another, and a template composing a base closes to the record its hand-written spelling is.
     */
    @Test
    void theRecordConstructorTakesItsSupertypesNameType() {
        String explicit = schema("explicit", """
                  json_name => !identifier_type { start_add: "@" }
                  other => !identifier_type { start_add: "$" }
                  json_record => !record { name_type: json_name  extension: ABSTRACT  fields: [] }
                  n => !record { supertypes: [json_record]  fields: [ { name: "@id"  type: text } ] }
                  folded => !identifier_type { normalization: NFKC_CASEFOLD }
                  fbase => !record { name_type: folded  extension: ABSTRACT  fields: [] }
                  box => <T> fbase & { name: T }
                  b => box<text>
                """);
        assertEquals(List.of(), read(explicit, "explicit", "!n { \"@id\": \"x\" }"));
        assertEquals(List.of(), read(explicit, "explicit", "!b { NAME: \"x\" }"));
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("restated", """
                      json_name => !identifier_type { start_add: "@" }
                      other => !identifier_type { start_add: "$" }
                      json_record => !record { name_type: json_name  extension: ABSTRACT  fields: [] }
                      n => !record { name_type: other  supertypes: [json_record]  fields: [] }
                    """)));
        assertTrue(thrown.getMessage().contains("is not its supertypes' 'json_name'"), thrown.getMessage());
    }

    /**
     * A profile's additions are its own, so the restricted-character rule does not judge them; a character that
     * renders as nothing, or as a character it is not, is the floor no addition lifts, refused at load.
     */
    @Test
    void aProfileMayNotAddAnInvisibleCharacter() {
        for (String added : new String[] {"continue_add: \"\\u{AD}\"", "medial: \"\\u{200B}\"",
                "medial: \"\\u{202E}\"", "continue_add: \"\\u{7}\"", "medial: \"\\u{A0}\""}) {
            var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    io.ltr8.tson.base.SchemaValidationException.class,
                    () -> Tson.standard().resolve(schema("invisible", "zw => !identifier_type { " + added + " }")),
                    added);
            assertTrue(thrown.getMessage().contains("which a profile may not add"), thrown.getMessage());
        }
        Tson.standard().resolve(schema("spaced", "words => !identifier_type { medial: \" \" }"));
    }

    /** Two supertypes with different name types compose names of two types, which no one type judges. */
    @Test
    void supertypesOfTwoNameTypesDoNotCompose() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("disagree", """
                      json_name => !identifier_type { start_add: "@$" }
                      words => !identifier_type { medial: " " }
                      node => !record { name_type: json_name  fields: [ { name: "@id"  type: text } ] }
                      row => !record { name_type: words  fields: [ { name: "first name"  type: text } ] }
                      both => node & row & {}
                    """)));
        assertTrue(thrown.getMessage().contains("have different name types"), thrown.getMessage());
    }

    /**
     * The default is a name type like any other: a plain record's names are {@code field_name}'s, set where it is
     * declared, so a translation's record cannot compose it -- its names were never declared as the other type's.
     * The record to compose is one declared over the translation's type.
     */
    @Test
    void aPlainSupertypeDoesNotComposeWithATranslationsRecord() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("mixed", """
                      json_name => !identifier_type { start_add: "@$" }
                      json_record => !record { name_type: json_name  extension: ABSTRACT  fields: [] }
                      address => { city: text }
                      node => json_record & address & { "@id": text }
                    """)));
        assertTrue(thrown.getMessage().contains("'json_name' and 'field_name'"), thrown.getMessage());

        String declared = schema("declared", """
                  json_name => !identifier_type { start_add: "@$" }
                  json_record => !record { name_type: json_name  extension: ABSTRACT  fields: [] }
                  address => json_record & { city: text }
                  node => json_record & address & { "@id": text }
                """);
        assertEquals(List.of(), read(declared, "declared", "!node { \"@id\": \"x\"  city: \"y\" }"));
    }

    /**
     * A document's field name is matched in its name type's form (§5.5): under a case-folding type {@code NAME} is
     * the field {@code name}, as an enum over the same type matches {@code OPEN} to {@code open}, and the two
     * spellings in one record are one field written twice.
     */
    @Test
    void aFieldNameIsMatchedInItsNameTypesForm() {
        String folded = schema("folded", """
                  folded => !identifier_type { normalization: NFKC_CASEFOLD }
                  row => !record { name_type: folded  fields: [ { name: name  type: text } ] }
                """);
        assertEquals(List.of(), read(folded, "folded", "!row { NAME: \"x\" }"));
        assertEquals(Diagnostic.Code.DUPLICATE_FIELD, only(read(folded, "folded",
                "!row { name: \"x\"  NAME: \"y\" }")).code());
    }

    /**
     * Under a folding name type a document's name and its field are spelled two ways, and each pointer takes its
     * own: the data path the name as written, the schema pointer the declaration; a repeat names the field and
     * where it was first given, as whatever spelling it was given in.
     */
    @Test
    void aFoldedNamesPathIsTheNameAsWritten() {
        String folded = schema("folded-path", """
                  folded => !identifier_type { normalization: NFKC_CASEFOLD }
                  row => !record { name_type: folded  fields: [ { name: count  type: int32 } ] }
                """);
        Diagnostic wrong = only(read(folded, "folded-path", "!row { COUNT: x }"));
        assertEquals("/COUNT", wrong.path().orElseThrow());
        assertEquals("/row/count", wrong.schemaPointer().orElseThrow());
        Diagnostic repeated = only(read(folded, "folded-path", "!row { COUNT: 1  Count: 2 }"));
        assertEquals("/Count", repeated.path().orElseThrow());
        assertTrue(repeated.message().contains("the field 'count' is already given, as 'COUNT' at 2:8:"),
                repeated.message());
    }

    /** A family's selector is found in its base's name form too, so a member is chosen however its name is cased. */
    @Test
    void aSelectorIsMatchedInItsBasesNameForm() {
        String family = schema("folded-family", """
                  folded => !identifier_type { normalization: NFKC_CASEFOLD }
                  base => !record { name_type: folded  extension: ABSTRACT  fields: [] }
                  pet => abstract base & { kind: text =?  name: text }
                  dog => pet & { kind?: = "dog"  breed: text }
                  holder => { p: pet }
                """);
        assertEquals(List.of(), read(family, "folded-family",
                "!holder { p: { KIND: \"dog\"  name: \"Rex\"  Breed: \"corgi\" } }"));
    }

    @Test
    void annotationNamesStayIdentifiersByTheGrammar() {
        assertEquals(1, Tson.standard().validate("{ a: @\"x y\" 1 }").size());
    }
}
