package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.OpenEntry;
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
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
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
            if (OpenEntry.isOpen(definition)) {
                parameters.put(name, OpenEntry.parameters(definition));
            }
        });
        return parameters;
    }

    private static TemplateParam param(String name, String type) {
        return new TemplateParam(name, TypeRef.of(type));
    }

    /** A type slot gives {@code type_kind}; a scalar slot of the applied constructor gives that slot's type. */
    @Test
    void aSlotGivesItsDeclaredType() {
        assertEquals(List.of(param("T", "type_kind"), param("N", "non_negative_integer")), parameters().get("vector"));
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
        assertEquals(List.of(param("T", "type_kind"), param("N", "T")), parameters().get("typed_by"));
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
        assertEquals(List.of(), tson.validate("!!import:\"" + ID + "\"\n!holder { n: c }"));
        List<Diagnostic> refused = tson.validate("!!import:\"" + ID + "\"\n!holder { n: z }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, refused.getFirst().code());
    }

    /** Uses unordered by IS-A are refused at the declaration: no argument could satisfy both. */
    @Test
    void usesThatDisagreeAreRefused() {
        List<Diagnostic> refused = Tson.standard().validateSchema("""
                !!id:"https://example.test/clash-1.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
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
        assertEquals(List.of(param("T", "type_kind")), core.get("set"));
    }

    // ── A template over a meta constructor is the meta's ─────────────────────────────

    private static List<Diagnostic> load(String declarations) {
        return Tson.standard().validateSchema("""
                !!id:"https://example.test/params-structure.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                """ + declarations + "}");
    }

    /**
     * {@code N} stands in {@code array.min_items}, so its type is the kernel's {@code non_negative_integer}, the slot's
     * own (§5.10): {@code vec<0>} is a valid application, and a text argument is refused. A schema declaring its own
     * entry under that name collides with the kernel's, which every closure reaching core holds (§2.2.3).
     */
    @Test
    void aConstructorTemplatesValueIsTheSlotsType() {
        assertEquals(List.of(), load("""
                  vec => <N> !array { element_type: text  min_items: N }
                  v0  => vec<0>
                """));
        List<Diagnostic> wide = load("""
                  vec    => <N> !array { element_type: text  min_items: N }
                  holder => { a: vec<"abc"> }
                """);
        assertEquals(1, wide.size(), wide::toString);
        assertTrue(wide.getFirst().message().contains("binds 'N' to 'abc'"), wide::toString);
        List<Diagnostic> collides = load("""
                  non_negative_integer => !integer ^ { min: 1 }
                """);
        assertEquals(1, collides.size(), collides::toString);
        assertTrue(collides.getFirst().message().contains("collides"), collides::toString);
    }

    /** A written type on such a template narrows the slot's type, and a type that is no narrowing of it is refused. */
    @Test
    void aConstructorTemplatesWrittenTypeNarrowsTheSlotsType() {
        assertEquals(List.of(), load("""
                  vec => <N: non_negative_integer> !array { element_type: text  max_items: N }
                  v3  => vec<3>
                """));
        List<Diagnostic> core = load("""
                  vec => <N: int8> !array { element_type: text  max_items: N }
                """);
        assertEquals(1, core.size(), core::toString);
        assertTrue(core.getFirst().message().contains("does not IS-A"), core::toString);
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
