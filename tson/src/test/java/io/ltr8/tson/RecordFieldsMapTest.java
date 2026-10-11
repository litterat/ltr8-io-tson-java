package io.ltr8.tson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code record.fields} is an ordered map from a field's name to its facts (SPEC-FEEDBACK.md #12). A name written
 * twice by its spelling is the map's duplicate key, refused where the explicit form is read; two spellings that are
 * one name under the record's name type are the linker's to refuse, since only a resolved name type can say so.
 * Declaration order is part of the value.
 */
class RecordFieldsMapTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            """;

    private static List<Diagnostic> validateSchema(String id, String declarations) {
        return Tson.standard().validateSchema(HEADER.formatted(id) + "{\n" + declarations + "\n}\n");
    }

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst();
    }

    @Test
    void theExplicitFormStatesFieldsAsAMapKeyedByName() {
        assertEquals(List.of(), validateSchema("explicit", """
                  point => !record { fields: { x => { type: int32 }  y => { type: int32  optional: true } } }
                """));
    }

    /**
     * One spelling twice is a repeated key of {@code fields}, which the map's own reader refuses: read as data
     * governed by meta.tn it is {@code DUPLICATE_MAP_KEY} at the repeated key, and at a schema's declaration it is
     * the same refusal restated against the declaration, as every failure of a constructor's vocabulary is.
     */
    @Test
    void aNameWrittenTwiceInTheExplicitFormIsTheMapsDuplicateKey() {
        Diagnostic read = only(Tson.standard().validate("""
                !!schema:"https://tson.io/2026/38/m/meta.tn"
                !record { fields: { x => { type: int32 }  x => { type: text } } }
                """));
        assertEquals(Diagnostic.Code.DUPLICATE_MAP_KEY, read.code(), read.toString());

        Diagnostic declared = only(validateSchema("twice", """
                  point => !record { fields: { x => { type: int32 }  x => { type: text } } }
                """));
        assertTrue(declared.message().contains("duplicate key 'x' in 'fields'"), declared.message());
        assertTrue(!declared.message().contains("are one"), "never the linker's name-type verdict");
    }

    /**
     * Two spellings that fold to one name are distinct keys, so the map admits them, and the linker refuses them
     * under the record's name type: the split is deliberate, the map judging spelling and the type judging names.
     */
    @Test
    void twoSpellingsOfOneNameAreTheLinkersToRefuse() {
        Diagnostic problem = only(validateSchema("folded", """
                  folded => !identifier_type { normalization: NFKC_CASEFOLD }
                  row => !record { name_type: folded  fields: { name => { type: text }  NAME => { type: text } } }
                """));
        assertTrue(problem.code() != Diagnostic.Code.DUPLICATE_MAP_KEY, problem.toString());
        assertTrue(problem.message().contains("'name' and 'NAME' are one value of its name_type 'folded'"),
                problem.message());
    }

    /** The brace form's own duplicate is the resolver's, which says which way the name was repeated. */
    @Test
    void aNameWrittenTwiceInTheBraceFormIsTheResolvers() {
        Diagnostic problem = only(validateSchema("brace", "  point => { x: int32  x: text }"));
        assertTrue(problem.message().contains("field 'x' is declared more than once"), problem.message());
    }

    @Test
    void fieldsKeepTheirDeclarationOrder() {
        RecordBody row = (RecordBody) Tson.standard().resolve(HEADER.formatted("ordered") + """
                {
                  base => { zeta: text  alpha: int32 }
                  row => base & { mid?: text  beta: text }
                }
                """).schema().entries().get("row").body();
        assertEquals(List.of("zeta", "alpha", "mid", "beta"), List.copyOf(row.fields().keySet()));
    }

    /** Order is part of the value, so the same fields in another order are another record. */
    @Test
    void twoRecordsDifferingOnlyInFieldOrderAreTwoValues() {
        Map.Entry<String, RecordField> a = Map.entry("a", RecordField.required(TypeRef.of("text")));
        Map.Entry<String, RecordField> b = Map.entry("b", RecordField.required(TypeRef.of("text")));
        assertNotEquals(RecordBody.of(RecordBody.fieldMap(a, b)), RecordBody.of(RecordBody.fieldMap(b, a)));
        assertEquals(RecordBody.of(RecordBody.fieldMap(a, b)), RecordBody.of(RecordBody.fieldMap(a, b)));
    }
}
