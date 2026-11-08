package io.ltr8.tson;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A reference ({@code meta.ref<T>}, the kernel's {@code reference}) is declared in any schema: one namespace per schema
 * (SPEC-FEEDBACK.md #7) puts the kernel's {@code ref} in every closure reaching core.tn or meta.tn, a schema a meta
 * layer governs among them, and the constructor applied directly is no different from the template that spells it.
 * What a reference may name is still judged: a type, never a constructor, whose types a kind names.
 */
class ReferenceDeclarationTest {

    private static final String KERNEL = "https://tson.io/2026/39/m/meta.tn";
    private static final String META = "https://tson.io/2026/39/m/meta.tn";
    private static final String CORE = "https://tson.io/2026/39/m/core.tn";

    private static String schema(String id, String firstImport, String secondImport, String declarations) {
        return """
                !!id:"%s"
                !!import:"%s"
                !!import:"%s"
                !schema {
                %s
                }""".formatted(id, firstImport, secondImport, declarations);
    }

    private static final String PICKS = """
              order => { id: text }
              rpick => { m: meta.ref<order> }""";

    /** The same, importing the kernel alone: its entries are named by their paths. */
    private static final String KERNEL_PICKS = """
              order => { id: text }
              rpick => { m: meta.ref<order> }""";

    @Test
    void anOrdinarySchemaDeclaresAReference() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("https://example.test/picks.tn", META, CORE,
                PICKS)));
    }

    /** A vocabulary schema declares one, and so does a schema importing it. */
    @Test
    void aVocabularyAndASchemaImportingItEachDeclareAReference() {
        String vocabularyId = "https://example.test/meta-picks.tn";
        String vocabulary = schema(vocabularyId, KERNEL, META, """
                  picked => { m: meta.ref<picked> }""");
        String importing = schema("https://example.test/governed-picks.tn", vocabularyId, CORE, PICKS);
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(vocabularyId, vocabulary)))));

        assertEquals(List.of(), tson.validateSchema(vocabulary), "the vocabulary itself declares a reference");
        assertEquals(List.of(), tson.validateSchema(importing));
    }

    @Test
    void anOrdinarySchemaAppliesTheReferenceConstructor() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("https://example.test/picks.tn", META, CORE,
                """
                  order => { id: text }
                  oref  => !meta.reference_type { of: order }
                  rpick => { m: oref }""")));
    }

    @Test
    void aSchemaImportingTheKernelAloneDeclaresAReference() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("https://example.test/meta-picks.tn", KERNEL,
                META, KERNEL_PICKS)));
    }

    /**
     * {@code meta.ref<record>} is one reference whose {@code of} is a constructor, so the slot holds a type's name: the
     * one spelling names types or values by what {@code of} names.
     */
    @Test
    void aReferenceToAConstructorNamesTypes() {
        assertEquals(List.of(), Tson.standard().validateSchema(schema("https://example.test/ref-to-constructor.tn",
                META, CORE, "  pick => { m: meta.ref<meta.record> }")));
    }
}
