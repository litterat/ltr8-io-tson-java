package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.io.ByteSource;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record's field names have a type ([TSON-SCHEMA] §5.2), and a JSON record's member names are its field names
 * ([TSON-JSON] §6.1): a member is matched and judged by its record's {@code name_type}, as a TSON text field
 * name is.
 */
class JsonFieldNameTypeTest {

    private static final String ID = "https://example.test/json-field-names.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-field-names.tn"
            !!meta:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            {
              json_name => !identifier_type { start_add: "@$" }
              node => !record { name_type: json_name  fields: {
                "@id" => { type: text }  label => { type: text } } }
              json_ref => !record { name_type: json_name  fields: {
                "$ref" => { type: text }  "$id" => { type: text  optional: true } } }
              typed => !record { name_type: json_name  fields: { "$type" => { type: text } } }
              address => json_record & { city: text }
              located => ld_node & address
              folded => !identifier_type { normalization: NFKC_CASEFOLD }
              frow => !record { name_type: folded  fields: { name => { type: text } } }
              fbase => !record { name_type: folded  extension: ABSTRACT  fields: {} }
              pet => abstract fbase & { kind: text =?  name: text }
              dog => pet & { kind?: = "dog"  breed: text }
              kennel => { p: pet }
              words => !identifier_type { continue_add: "-"  medial: " " }
              row => !record { name_type: words  fields: { "first name" => { type: text } } }
              json_record => !record { name_type: json_name  extension: ABSTRACT  fields: {} }
              ld_node => json_record & { "@id": text  label: text }
              ld_holder => { n: ld_node }
              ld_name => !identifier_type { start_add: "@" }
              ld_only => !record { name_type: ld_name  fields: { "@id" => { type: text } } }
              plain => { a: text }
            }
            """;

    private static List<Diagnostic> read(String typeName, String source) {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(source)) {
            Json.standard().withSchemas(tson.schemaRegistry()).treeReader().withDiagnostics(receiver).withSchema(ID)
                    .readAs(bytes, typeName);
        }
        return problems;
    }

    @Test
    void aMemberNameIsReadByItsRecordsFieldNameType() {
        assertEquals(List.of(), read("node", "{\"@id\": \"urn:x\", \"label\": \"y\"}"));
        assertEquals(List.of(), read("row", "{\"first name\": \"Ada\"}"));
    }

    /**
     * [TSON-JSON] §3.2 gives meaning to three names and no others, so a JSON Schema translation's {@code $ref} and
     * {@code $id} are ordinary members of a record whose name type admits them.
     */
    @Test
    void aDollarNameOutsideTheThreeIsAnOrdinaryField() {
        assertEquals(List.of(), read("json_ref", "{\"$ref\": \"#/defs/a\", \"$id\": \"urn:a\"}"));
    }

    /**
     * A record that declares one of the three obscures it: a leading {@code $type} is the annotation member, read
     * as a type annotation naming a type, so the field is never read -- the undesirable result §3.2 warns of.
     */
    @Test
    void aRecordDeclaringATagMemberObscuresIt() {
        List<Diagnostic> problems = read("typed", "{\"$type\": \"x\"}");
        assertEquals(List.of(Diagnostic.Code.TYPE_MISMATCH), problems.stream().map(Diagnostic::code).toList(),
                problems.toString());
    }

    /** Two records declared over one name type compose, and both one's names and the other's read from JSON. */
    @Test
    void twoRecordsOfOneNameTypeCompose() {
        assertEquals(List.of(), read("located", "{\"@id\": \"urn:x\", \"label\": \"y\", \"city\": \"z\"}"));
    }

    /** A member name is matched in the record's name type's form, and so is a family's selector (§5.5). */
    @Test
    void aMemberNameIsMatchedInItsNameTypesForm() {
        assertEquals(List.of(), read("frow", "{\"NAME\": \"x\"}"));
        assertEquals(List.of(), read("kennel", "{\"p\": {\"KIND\": \"dog\", \"name\": \"Rex\", \"Breed\": \"c\"}}"));
    }

    /**
     * An undeclared member is judged by its record's name type, not the default's: one the type admits and the
     * record does not declare is unknown, and one outside the type -- here a {@code $} where only {@code @} starts
     * a name of {@code ld_only}, and a {@code /} in a plain record -- can be no field and is unknown. Neither is a
     * name-policy refusal, and each lists the record's fields; a declared name type is named with its reason, and
     * the default, whose rules are any identifier's, is not.
     */
    @Test
    void anUndeclaredMemberIsJudgedByItsRecordsNameType() {
        for (String[] at : new String[][] {
                {"node", "{\"@id\": \"urn:x\", \"label\": \"y\", \"$ref\": 1}", "/$ref", null},
                {"ld_only", "{\"@id\": \"urn:x\", \"$ref\": 1}", "/$ref", "'ld_name'"},
                {"plain", "{\"a\": \"x\", \"react/display-name\": 1}", "/react~1display-name", null}}) {
            List<Diagnostic> problems = read(at[0], at[1]);
            assertEquals(1, problems.size(), problems::toString);
            Diagnostic unknown = problems.getFirst();
            assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, unknown.code(), unknown::toString);
            assertEquals(at[2], unknown.path().orElseThrow());
            assertTrue(at[3] == null ? !unknown.message().contains("name type")
                    : unknown.message().contains("not a value of the record's name type " + at[3]), unknown::message);
        }
    }

    /**
     * Under a folding name type a member and its field are spelled two ways, and each pointer takes its own: the
     * data path the member as written, the only spelling RFC 6901 finds in the document, the schema pointer the
     * declaration. A repeat is reported at the repeat, naming the field and where it was first given.
     */
    @Test
    void aFoldedMembersPathIsTheMemberAsWritten() {
        Diagnostic wrong = read("frow", "{\"NAME\": 5}").getFirst();
        assertEquals("/NAME", wrong.path().orElseThrow());
        assertEquals("/frow/name", wrong.schemaPointer().orElseThrow());
        List<Diagnostic> repeated = read("frow", "{\"name\": \"a\", \"NAME\": \"b\"}");
        assertEquals(1, repeated.size(), repeated::toString);
        assertEquals(Diagnostic.Code.DUPLICATE_FIELD, repeated.getFirst().code());
        assertEquals("/NAME", repeated.getFirst().path().orElseThrow());
        assertTrue(repeated.getFirst().message().contains("the field 'name' is already given, as 'name' at 1:2:1"),
                repeated.getFirst()::message);
    }

    /** A record composing a fieldless base takes the base's field name type, and reads untagged where it is typed. */
    @Test
    void aCompositionReadsItsMembersByItsBasesFieldNameType() {
        assertEquals(List.of(), read("ld_holder", "{\"n\": {\"@id\": \"urn:x\", \"label\": \"y\"}}"));
    }
}
