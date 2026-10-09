package io.ltr8.tson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.net.MediaType;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code net.tn}'s {@code media_type} and meta's {@code media_type_type} facets, under a schema and without one. */
class MediaTypeSchemaTest {

    private static final String ID = "https://example.test/media.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            !!import:"https://tson.io/2026/38/m/net.tn"
            {
              content_type => !media_type_type { allow_parameters: true }
              json_type => !media_type_type { types: [application]  suffixes: [json] }
              attachment => { data: bytes  type: media_type }
              message => { body: text  content_type: content_type }
              document => { type: json_type }
            }
            """.formatted(ID);

    private static Tson tson() {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        return tson;
    }

    private static List<Diagnostic> validate(String body) {
        return tson().validate("!!schema:\"" + ID + "\"\n" + body);
    }

    private static Diagnostic.Code only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst().code();
    }

    @Test
    void aMediaTypeIsReadToItsValue() {
        MediaType type = tson().treeReader()
                .read("!!schema:\"" + ID + "\"\n!attachment { data: !bytes \"AA==\"  type: \"Image/PNG\" }")
                .get("type").as(MediaType.class).orElseThrow();
        assertEquals("image/png", type.toString());
    }

    @Test
    void mediaTypeAdmitsNoParameters() {
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                only(validate("!attachment { data: !bytes \"AA==\"  type: \"text/plain;charset=utf-8\" }")));
    }

    @Test
    void anInstanceMayAdmitParameters() {
        assertEquals(List.of(), validate("!message { body: hi  content_type: \"text/plain; Charset=\\\"UTF-8\\\"\" }"));
        assertEquals("text/plain;charset=utf-8", tson().treeReader()
                .read("!!schema:\"" + ID + "\"\n!message { body: hi  content_type: \"text/plain; Charset=UTF-8\" }")
                .get("content_type").as(MediaType.class).orElseThrow().toString());
    }

    /** {@code suffixes: [json]} admits {@code application/json} itself and every {@code +json} subtype. */
    @Test
    void theTypesAndSuffixesFacets() {
        assertEquals(List.of(), validate("!document { type: \"application/json\" }"));
        assertEquals(List.of(), validate("!document { type: \"application/ld+json\" }"));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, only(validate("!document { type: \"text/json\" }")));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                only(validate("!document { type: \"application/xml\" }")));
    }

    @Test
    void aMediaRangeIsNoMediaType() {
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID,
                only(validate("!attachment { data: !bytes \"AA==\"  type: \"image/*\" }")));
    }

    @Test
    void aRefinementOnlyNarrows() {
        assertThrows(SchemaValidationException.class, () -> Tson.standard().resolve("""
                !!id:"https://example.test/widen.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                !!import:"https://tson.io/2026/38/m/net.tn"
                {
                  json_type => !media_type_type { suffixes: [json] }
                  wider => !json_type ^ { suffixes: [json xml] }
                }
                """));
        assertThrows(SchemaValidationException.class, () -> Tson.standard().resolve("""
                !!id:"https://example.test/regrant.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                !!import:"https://tson.io/2026/38/m/net.tn"
                { with_params => !media_type ^ { allow_parameters: true } }
                """));
    }

    @Test
    void anEmptyOrMalformedListIsIncoherent() {
        for (String facet : List.of("types: []", "suffixes: [\"a+b\"]", "types: [\"*\"]")) {
            assertThrows(SchemaValidationException.class, () -> Tson.standard().resolve("""
                    !!id:"https://example.test/incoherent.tn"
                    !!meta:"https://tson.io/2026/38/m/meta.tn"
                    !!import:"https://tson.io/2026/38/m/core.tn"
                    !!import:"https://tson.io/2026/38/m/net.tn"
                    { odd => !media_type_type { %s } }
                    """.formatted(facet)), facet);
        }
    }

    @Test
    void aSchemalessMediaTypeIsChecked() {
        assertEquals(List.of(), Tson.standard().validate("{ type: !media_type \"application/ld+json\" }"));
        assertTrue(Tson.standard().validate("{ type: !media_type \"text/*\" }").size() == 1);
    }
}
