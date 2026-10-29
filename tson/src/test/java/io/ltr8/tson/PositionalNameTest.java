package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonTypeReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * §5.6's positional form in a data document, which values by name ([TSON-SCHEMA] §5.12) do not reach: a data
 * document has no namespace to resolve a name in, so a bare token at a record position fills the one positional
 * field whatever that field's type. A kind-typed field reaches a data document through core.tn's own {@code kind};
 * a field typed by a kernel record ({@code type_ref}, {@code record_field}) does not, since an ordinary schema
 * imports only ordinary ones (§2.2.3). The one schema-document case here is the contrast: there a bare token fills
 * the positional field only where that field is atom-typed, which {@code record_field}'s {@code type_kind} is.
 */
class PositionalNameTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/positional-name.tn"
            !!meta:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            {
              named  => { v: text }
              slot   => { type: kind<record> }
              holder => { c?: named  p?: slot  q?: kind<record> }
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

    /** {@code named}'s one field is {@code v}, text: the token fills it. */
    @Test
    void aTokenFillsAnAtomTypedPositionalField() {
        assertEquals(List.of(), read("!holder { c: some_text }"));
    }

    /**
     * {@code slot}'s one field is {@code type}, a {@code kind<record>}: in a data document a name fills it, and is
     * judged a member of the kind in the governing schema's namespace.
     */
    @Test
    void aNameFillsAKindTypedPositionalFieldInADataDocument() {
        assertEquals(List.of(), read("!holder { p: named }"));
        assertFalse(read("!holder { p: some_type }").isEmpty(), "a name that is no member is refused");
    }

    /** A kind-typed slot holds a type's name, one segment: a dotted token is refused by the grammar, never read by name. */
    @Test
    void aDottedTokenIsReadAsThePositionalField() {
        List<Diagnostic> reported = read("!holder { q: orders.methods.place_order }");
        assertFalse(reported.isEmpty());
        reported.forEach(diagnostic -> assertFalse(diagnostic.message().contains("found the name"),
                diagnostic.message()));
    }

    /**
     * In a schema document the same fill: {@code record_field}'s positional field is {@code type}, an atom, so
     * {@code p => text} is {@code p => { type: text }}, which is what the sugar {@code p: text} desugars to.
     */
    @Test
    void aNameFillsThePositionalRecordFieldInASchemaDocument() {
        for (String fields : List.of("{ p => text }", "{ p => { type: text } }")) {
            assertEquals(List.of(), Tson.standard().validateSchema("""
                    !!id:"https://example.test/positional-name-schema.tn"
                    !!meta:"https://tson.io/2026/39/m/meta.tn"
                    !!import:"https://tson.io/2026/39/m/core.tn"
                    {
                      r => !record { fields: %s }
                    }""".formatted(fields)), fields);
        }
    }
}
