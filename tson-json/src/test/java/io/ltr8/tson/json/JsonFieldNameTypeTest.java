package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.io.ByteSource;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A record's field names have a type (SPEC-FEEDBACK.md #10), and a JSON record's member names are its field names
 * ([TSON-JSON] §6.1): a member is matched and judged by its record's {@code name_type}, as a TSON text field
 * name is.
 */
class JsonFieldNameTypeTest {

    private static final String ID = "https://example.test/json-field-names.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-field-names.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              json_name => !identifier_type { start_add: "@$" }
              node => !record { name_type: json_name  fields: [
                { name: "@id"  type: text }  { name: label  type: text } ] }
              ref => !record { name_type: json_name  fields: [
                { name: "$ref"  type: text }  { name: "$id"  type: text  optional: true } ] }
              typed => !record { name_type: json_name  fields: [ { name: "$type"  type: text } ] }
              address => { city: text }
              located => ld_node & address
              folded => !identifier_type { normalization: NFKC_CASEFOLD }
              frow => !record { name_type: folded  fields: [ { name: name  type: text } ] }
              fbase => !record { name_type: folded  extension: ABSTRACT  fields: [] }
              pet => abstract fbase & { kind: text =?  name: text }
              dog => pet & { kind?: = "dog"  breed: text }
              kennel => { p: pet }
              words => !identifier_type { continue_add: "-"  medial: " " }
              row => !record { name_type: words  fields: [ { name: "first name"  type: text } ] }
              json_record => !record { name_type: json_name  extension: ABSTRACT  fields: [] }
              ld_node => json_record & { "@id": text  label: text }
              ld_holder => { n: ld_node }
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
        assertEquals(List.of(), read("ref", "{\"$ref\": \"#/defs/a\", \"$id\": \"urn:a\"}"));
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

    /** A plain supertype composed with a translation's record takes its name type: both names read from JSON. */
    @Test
    void aPlainSupertypeComposesWithATranslationsRecord() {
        assertEquals(List.of(), read("located", "{\"@id\": \"urn:x\", \"label\": \"y\", \"city\": \"z\"}"));
    }

    /** A member name is matched in the record's name type's form, and so is a family's selector (§5.5). */
    @Test
    void aMemberNameIsMatchedInItsNameTypesForm() {
        assertEquals(List.of(), read("frow", "{\"NAME\": \"x\"}"));
        assertEquals(List.of(), read("kennel", "{\"p\": {\"KIND\": \"dog\", \"name\": \"Rex\", \"Breed\": \"c\"}}"));
    }

    /** A record composing a fieldless base takes the base's field name type, and reads untagged where it is typed. */
    @Test
    void aCompositionReadsItsMembersByItsBasesFieldNameType() {
        assertEquals(List.of(), read("ld_holder", "{\"n\": {\"@id\": \"urn:x\", \"label\": \"y\"}}"));
    }
}
