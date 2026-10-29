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
 * A reference ({@code ref<T>}, the meta-kernel's {@code ref_type}) is declared in any schema: one namespace per schema
 * (SPEC-FEEDBACK.md #7) puts the kernel's {@code ref} in every closure reaching core.tn or meta.tn, a schema a meta
 * layer governs among them, and the constructor applied directly is no different from the template that spells it.
 * What a reference may name is still judged: a type, never a constructor, whose types a kind names.
 */
class ReferenceDeclarationTest {

    private static final String KERNEL = "https://tson.io/2026/39/m/meta-kernel.tn";
    private static final String META = "https://tson.io/2026/39/m/meta.tn";
    private static final String CORE = "https://tson.io/2026/39/m/core.tn";

    private static String schema(String id, String meta, String imported, String declarations) {
        return """
                !!id:"%s"
                !!import:"%s"
                !!import:"%s"
                !schema {
                %s
                }""".formatted(id, meta, imported, declarations);
    }

    private static final String PICKS = """
              order => { id: text }
              rpick => { m: ref<order> }""";

    @Test
    void anOrdinarySchemaDeclaresAReference() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("https://example.test/picks.tn", META, CORE,
                PICKS)));
    }

    /** A meta layer declares one, and so does a schema it governs. */
    @Test
    void aSchemaAMetaLayerGovernsDeclaresAReference() {
        String metaId = "https://example.test/meta-picks.tn";
        String layer = schema(metaId, KERNEL, META, """
                  picked => { m: ref<picked> }""");
        String governed = schema("https://example.test/governed-picks.tn", metaId, CORE, PICKS);
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(metaId, layer)))));

        assertEquals(List.of(), tson.validateSchema(layer), "the meta layer itself declares a reference");
        assertEquals(List.of(), tson.validateSchema(governed));
    }

    @Test
    void anOrdinarySchemaAppliesTheReferenceConstructor() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("https://example.test/picks.tn", META, CORE,
                """
                  order => { id: text }
                  oref  => !ref_type { of: order }
                  rpick => { m: oref }""")));
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
                META, CORE, "  pick => { m: ref<record> }"));

        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(problem -> problem.message().contains("kind<record>")),
                problems.toString());
    }
}
