package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code template_param.type} end to end: each parameter of an open entry records the type an argument for it is
 * read as, derived from the positions it stands in ([TSON-SCHEMA] §5.10), and the kind follows from it.
 */
class TemplateParamTypeTest {

    private static final String ID = "https://example.test/params-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/params-1.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              percent  => !integer ^ { min: 0  max: 100 }
              vector   => <T, N> !array { element_type: T  min_items: N  max_items: N }
              counted  => <C> { c?: percent ~ C }
              outer    => <X> counted<X>
              typed_by => <T, N> { w?: T ~ N }
              names    => <M> !enum [a b M]
              named    => names<c>
              holder   => { n: named }
            }
            """;

    private static Map<String, List<TemplateParam>> parameters() {
        Map<String, List<TemplateParam>> parameters = new LinkedHashMap<>();
        Tson.standard().resolve(SCHEMA).schema().entries().forEach((name, definition) -> {
            if (definition.body() instanceof TemplateBody held) {
                parameters.put(name, held.parameters());
            }
        });
        return parameters;
    }

    private static TemplateParam param(String name, String type) {
        return new TemplateParam(name, TypeRef.of(type));
    }

    /** A type slot gives {@code type_ref}; a scalar slot of the applied constructor gives that slot's type. */
    @Test
    void aSlotGivesItsDeclaredType() {
        assertEquals(List.of(param("T", "type_ref"), param("N", "non_negative_integer")), parameters().get("vector"));
    }

    /** A routed default is read as the field's own declared type, not as {@code record_field.value}'s. */
    @Test
    void aRoutedDefaultGivesTheFieldsType() {
        assertEquals(List.of(param("C", "percent")), parameters().get("counted"));
    }

    /** A parameter passed to another template takes that template's type for the position. */
    @Test
    void anArgumentTakesTheCalleesType() {
        assertEquals(List.of(param("X", "percent")), parameters().get("outer"));
    }

    /** A field typed by a parameter makes the routed parameter's type that parameter. */
    @Test
    void aTypeMayNameAnEarlierParameter() {
        assertEquals(List.of(param("T", "type_ref"), param("N", "T")), parameters().get("typed_by"));
    }

    /**
     * §5.6's positional form: {@code !enum [a b M]} binds {@code members}, so {@code M} is a member and
     * {@code names<c>} reads {@code c} as one rather than as a type named {@code c}.
     */
    @Test
    void thePositionalFormIsWalkedLikeTheRecordItSpells() {
        assertEquals(List.of(param("M", "text")), parameters().get("names"));

        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, SCHEMA)))));
        assertEquals(List.of(), tson.validateSchema(SCHEMA));
        assertEquals(List.of(), tson.validate("!!schema:\"" + ID + "\"\n!holder { n: c }"));
        List<Diagnostic> refused = tson.validate("!!schema:\"" + ID + "\"\n!holder { n: z }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, refused.getFirst().code());
    }

    /** Uses unordered by IS-A are refused at the declaration: no argument could satisfy both. */
    @Test
    void usesThatDisagreeAreRefused() {
        List<Diagnostic> refused = Tson.standard().validateSchema("""
                !!id:"https://example.test/clash-1.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  clash => <N> { a?: text ~ N  b?: int32 ~ N }
                }
                """);
        assertEquals(1, refused.size(), refused.toString());
        assertTrue(refused.getFirst().message().contains("none of them IS-A the others"), refused.toString());
    }

    /** The bundled templates record what their positions give. */
    @Test
    void coresTemplatesRecordTheirTypes() {
        Map<String, List<TemplateParam>> core = parameters();
        assertEquals(List.of(param("S", "schema_identity")), core.get("extern_of"));
        assertEquals(List.of(param("S", "schema_identity"), param("T", "type_name")), core.get("extern_type"));
        assertEquals(List.of(param("T", "type_ref")), core.get("set"));
    }

    // ── A template over a meta constructor is the meta's ─────────────────────────────

    private static List<Diagnostic> load(String declarations) {
        return Tson.standard().validateSchema("""
                !!id:"https://example.test/params-structure.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                """ + declarations + "}");
    }

    /**
     * {@code N} stands in {@code array.min_items}, so its type is the meta's {@code non_negative_integer}, judged in
     * the structure namespace (§5.10): a schema's own entry under that name is another type and never stands in for
     * it, so {@code vec<0>} is a valid application however narrowly the schema defines its own.
     */
    @Test
    void aConstructorTemplatesValueIsJudgedInTheStructureNamespace() {
        assertEquals(List.of(), load("""
                  non_negative_integer => !integer ^ { min: 1 }
                  vec => <N> !array { element_type: text  min_items: N }
                  v0  => vec<0>
                """));
        List<Diagnostic> wide = load("""
                  non_negative_integer => !text ^ {}
                  vec    => <N> !array { element_type: text  min_items: N }
                  holder => { a: vec<"abc"> }
                """);
        assertEquals(1, wide.size(), wide::toString);
        assertTrue(wide.getFirst().message().contains("binds 'N' to 'abc'"), wide::toString);
    }

    /** A written type on such a template narrows within the meta's vocabulary, and a schema type is not there. */
    @Test
    void aConstructorTemplatesWrittenTypeNamesTheMetasVocabulary() {
        assertEquals(List.of(), load("""
                  vec => <N: non_negative_integer> !array { element_type: text  max_items: N }
                  v3  => vec<3>
                """));
        List<Diagnostic> core = load("""
                  vec => <N: int8> !array { element_type: text  max_items: N }
                """);
        assertEquals(1, core.size(), core::toString);
        assertTrue(core.getFirst().message().contains("not in the structure namespace"), core::toString);
    }

    /** A record template's fields are the schema's, and so is a written type on its parameters. */
    @Test
    void aRecordTemplateReadsItsParametersInTheSchema() {
        assertEquals(List.of(), load("""
                  note => <N: int8> { w?: integer ~ N }
                  n3   => note<3>
                """));
    }
}
