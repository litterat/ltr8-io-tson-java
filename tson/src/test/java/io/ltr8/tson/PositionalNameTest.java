package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonTypeReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.6's positional form in a data document, which values by name (SPEC-FEEDBACK.md #13) do not reach: a data
 * document has no namespace to resolve a name in, so a bare token at a record position fills the one positional
 * field whatever that field's type, as it always has. A schema that imports the kernel can type a field by its
 * records, which is the one way a data document reaches these positions. The one schema-document case here is
 * the contrast: the same token in a constructor's binding record is a value by name, not a product-typed fill.
 */
class PositionalNameTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/positional-name.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/meta-kernel.tn"
            {
              holder => { p?: record_field  q?: record  t?: type_ref }
            }""";

    private static List<Diagnostic> read(String data) {
        Tson tson = Tson.standard();
        TsonTypeReader<?> holder = tson.treeRegistry().compile(tson.resolve(SCHEMA)).get("holder");
        List<Diagnostic> reported = new ArrayList<>();
        Object value = holder.read(TestDocuments.document(data, reported::add));
        if (reported.isEmpty()) {
            assertNotNull(value);
        }
        return reported;
    }

    /** {@code type_ref}'s one field is {@code name}, a {@code type_name}: the token fills it. */
    @Test
    void aTypeReferenceKeepsItsBareSpelling() {
        assertEquals(List.of(), read("!holder { t: some_type  p: { type: some_type } }"));
    }

    /** {@code record_field}'s one field is {@code type}, a {@code type_ref}: in a data document a name fills it. */
    @Test
    void aNameFillsAProductTypedPositionalFieldInADataDocument() {
        assertEquals(List.of(), read("!holder { p: some_type }"));
    }

    /** {@code record}'s one field is {@code fields}, a map: the token fills it, and is judged there as a map. */
    @Test
    void aDottedTokenIsReadAsThePositionalField() {
        List<Diagnostic> reported = read("!holder { q: orders.methods.place_order }");
        assertFalse(reported.isEmpty());
        reported.forEach(diagnostic -> assertFalse(diagnostic.message().contains("found the name"),
                diagnostic.message()));
    }

    /** The same spelling in a schema document: a constructor's binding record is read as a schema document's value. */
    @Test
    void aNameIsNoPositionalRecordFieldInASchemaDocument() {
        List<Diagnostic> problems = Tson.standard().validateSchema("""
                !!id:"https://example.test/positional-name-schema.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  r => !record { fields: { p => text } }
                }""");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("expected a record for 'record_field', found the name 'text'"),
                problems.getFirst().message());
        assertEquals(List.of(), Tson.standard().validateSchema("""
                !!id:"https://example.test/positional-name-schema-explicit.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  r => !record { fields: { p => { type: text } } }
                }"""));
    }
}
