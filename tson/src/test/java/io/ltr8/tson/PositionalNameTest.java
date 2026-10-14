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
 * §5.6's positional form against a value written by name (SPEC-FEEDBACK.md #13), in a data document: at a
 * record-typed position a bare token fills the one positional field only where that field is atom-typed. A
 * schema that imports the kernel can type a field by its records, which is the one way a data document reaches
 * these positions.
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

    /** {@code type_ref}'s one field is {@code name}, a {@code type_name}: an atom, so the token fills it. */
    @Test
    void aTypeReferenceKeepsItsBareSpelling() {
        assertEquals(List.of(), read("!holder { t: some_type  p: { type: some_type } }"));
    }

    /** {@code record_field}'s one field is {@code type}, a {@code type_ref}: a product, so a name does not fill it. */
    @Test
    void aNameIsNoPositionalRecordField() {
        List<Diagnostic> reported = read("!holder { p: some_type }");
        assertEquals(1, reported.size(), reported.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, reported.getFirst().code());
        assertEquals("/p", reported.getFirst().path().orElseThrow());
        assertTrue(reported.getFirst().message().contains("found the name 'some_type'"),
                reported.getFirst().message());
    }

    /** {@code record}'s one field is {@code fields}, a map: a dotted name is a name there too. */
    @Test
    void aDottedNameIsNoPositionalFieldMap() {
        List<Diagnostic> reported = read("!holder { q: orders.methods.place_order }");
        assertEquals(1, reported.size(), reported.toString());
        assertTrue(reported.getFirst().message().contains("found the name 'orders.methods.place_order'"),
                reported.getFirst().message());
    }

    /** A token that is no name still fills the field, and is judged there as it always was. */
    @Test
    void aTokenThatIsNoNameStillFillsTheField() {
        List<Diagnostic> reported = read("!holder { p: 42 }");
        assertFalse(reported.isEmpty());
        reported.forEach(diagnostic -> assertFalse(diagnostic.message().contains("found the name"),
                diagnostic.message()));
    }
}
