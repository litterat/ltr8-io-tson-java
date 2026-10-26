package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reference ({@code ref<T>}, the meta-kernel's {@code ref_type}) is kernel vocabulary. It names an entry whose value
 * is a value of {@code T}, and an entry holds a value only where a namespace type can be declared: in a schema whose
 * {@code !!meta} is the meta-kernel. core.tn declares no {@code ref}, an ordinary schema imports no kernel-governed
 * schema (§2.2.3), and the type-name namespace is not inherited through {@code !!meta}, so in an ordinary schema
 * {@code ref<order>} names nothing and is refused as an unresolved name. The structure namespace still supplies the
 * constructor, so {@code !ref_type { of: order }} applied directly is refused by the linker for the schema's level.
 */
class ReferenceLevelTest {

    private static final String KERNEL = "https://tson.io/2026/38/m/meta-kernel.tn";
    private static final String META = "https://tson.io/2026/38/m/meta.tn";
    private static final String CORE = "https://tson.io/2026/38/m/core.tn";

    private static String schema(String id, String meta, String imported, String declarations) {
        return """
                !!id:"%s"
                !!meta:"%s"
                !!import:"%s"
                {
                %s
                }""".formatted(id, meta, imported, declarations);
    }

    private static final String PICKS = """
              order => { id: text }
              rpick => { m: ref<order> }""";

    private static void assertRefIsUnresolved(List<Diagnostic> problems) {
        assertFalse(problems.isEmpty(), "ref<order> in a schema that is not kernel-governed names nothing");
        problems.forEach(problem -> assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code(), problem.toString()));
        assertTrue(problems.stream().anyMatch(problem -> problem.message().contains("'ref'")), problems.toString());
    }

    /** meta.tn imports the meta-kernel, which declares {@code ref}; none of it reaches the schema meta.tn governs. */
    @Test
    void anOrdinarySchemaCannotNameAReference() {
        assertRefIsUnresolved(Tson.standard().validateSchema(schema("https://example.test/picks.tn", META, CORE,
                PICKS)));
    }

    /** A meta layer's own closure holds the kernel's {@code ref}, and a schema it governs still does not reach it. */
    @Test
    void aSchemaAMetaLayerGovernsCannotNameAReference() {
        String metaId = "https://example.test/meta-picks.tn";
        String layer = schema(metaId, KERNEL, META, """
                  picked => { m: ref<picked> }""");
        String governed = schema("https://example.test/governed-picks.tn", metaId, CORE, PICKS);
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(metaId, layer)))));

        assertEquals(List.of(), tson.validateSchema(layer), "the meta layer itself declares a reference");
        assertRefIsUnresolved(tson.validateSchema(governed));
    }

    @Test
    void anOrdinarySchemaCannotApplyTheReferenceConstructor() {
        List<Diagnostic> problems = Tson.standard().validateSchema(schema("https://example.test/picks.tn", META, CORE,
                """
                  order => { id: text }
                  oref  => !ref_type { of: order }
                  rpick => { m: oref }"""));

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().startsWith("'oref' is a reference (ref_type), but"),
                problems.toString());
    }

    @Test
    void aKernelGovernedSchemaDeclaresAReference() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("https://example.test/meta-picks.tn", KERNEL,
                META, PICKS)));
    }

    /** A constructor's types are named by a kind: {@code ref<record>} is refused, and the refusal names the kind. */
    @Test
    void aReferenceToAConstructorIsRefused() {
        List<Diagnostic> problems = Tson.standard().validateSchema(schema("https://example.test/ref-to-constructor.tn",
                KERNEL, META, "  pick => { m: ref<record> }"));

        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(problem -> problem.message().contains("kind<record>")),
                problems.toString());
    }
}
