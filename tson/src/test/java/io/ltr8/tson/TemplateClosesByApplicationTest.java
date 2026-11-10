package io.ltr8.tson;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.source.SchemaSource;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A template closes by application, never by construction.</b> {@code C<...>} substitutes a template's
 * parameters away ([TSON-SCHEMA] §5.10); {@code !C { ... }} fills a <em>constructor's</em> own vocabulary
 * (§4.2). They are different operations, and a template named at a construction site is an author error.
 *
 * <p><b>It is a schema error, never an {@code IllegalStateException}</b> -- this project's spelling of "an
 * internal invariant broke", on which `tson validate` prints the please-report-it banner and exits <b>70</b>.
 *
 * <p>End to end through {@link Tson} rather than at {@code DefinitionResolver}, because the construction
 * head resolves against the schema's namespace -- which needs a real imported vocabulary, and
 * because what is being asserted is the user-visible half: the problem arrives as a collected {@link
 * Diagnostic}, not as an exception past the collector.
 */
class TemplateClosesByApplicationTest {

    private static final String VOCABULARY = "https://example.test/m.tn";
    private static final String USER = "https://example.test/u.tn";

    /**
     * Two templates in one vocabulary schema, both open records: a fresh record body holding {@code [T]}, and a
     * composition. Both are templates, which is the only thing the rule turns on.
     */
    private static String vocabulary(String extraDeclarations) {
        return """
                !!id:"https://example.test/m.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  base       => {}
                  my_vec     => <T> { items: [T] }
                  box        => <T> base & { value: T }
                  %s
                }
                """.formatted(extraDeclarations);
    }

    private static final String VOCABULARY_SOURCE = vocabulary("");

    /**
     * The problems the vocabulary schema itself reports, with {@code extraDeclarations} -- the closing cases --
     * declared beside the templates.
     */
    private static List<Diagnostic> vocabularyProblems(String extraDeclarations) {
        String source = vocabulary(extraDeclarations);
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(VOCABULARY, source)))));
        return tson.validateSchema(source);
    }

    private static List<Diagnostic> schemaProblems(String body) {
        String user = """
                !!id:"https://example.test/u.tn"
                !!import:"https://example.test/m.tn"
                !schema { %s }
                """.formatted(body);
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(VOCABULARY, VOCABULARY_SOURCE, USER, user)))));
        return tson.validateSchema(user);
    }

    /** The vocabulary itself is fine: declaring a template is not the mistake, applying one wrongly is. */
    @Test
    void theTemplatesThemselvesResolve() {
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(VOCABULARY, VOCABULARY_SOURCE)))));

        assertEquals(List.of(), tson.validateSchema(VOCABULARY_SOURCE));
    }

    @Test
    void aConstructorTemplateAtAConstructionSiteIsAnAuthorError() {
        List<Diagnostic> problems = schemaProblems("n => !my_vec { items: [text] }");

        assertEquals(1, problems.size(), problems::toString);
        Diagnostic only = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, only.code());
        assertTrue(only.message().contains("is a template taking 1 type argument [T]"), only::toString);
        assertTrue(only.message().contains("my_vec<...>"), only::toString);
        // Located at the declaration the author wrote, not at the template's own line in another document.
        assertEquals("/n", only.schemaPointer().orElseThrow());
    }

    /**
     * The rule is about being a template, not about carrying {@code ~}, so an unmarked one is refused by the
     * same check. It used to reach the {@code constructor: false} branch and be told "did you mean atom
     * refinement ({@code !box ^ { ... }})?" -- a suggestion that cannot work, the problem being the missing
     * argument list rather than the missing caret.
     */
    @Test
    void anUnmarkedTemplateGetsTheSameAdviceRatherThanTheRefinementHint() {
        List<Diagnostic> problems = schemaProblems("b => !box { value: text }");

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("box<...>"), problems::toString);
        assertFalse(problems.getFirst().message().contains("atom refinement"), problems::toString);
    }

    /** An application of a template closes to the record its arguments complete. */
    @Test
    void aTemplateClosesByApplication() {
        assertEquals(List.of(), vocabularyProblems("tags => my_vec<text>"));
        assertEquals(1, vocabularyProblems("tags => my_vec<no_such_type>").size());
    }

    /**
     * A refinement template pinning a slot to its parameter is refused at the declaration: a field's value is never a
     * parameter, whatever the field's type -- {@code element_type} is typed {@code meta.type_ref}, and its pin is a
     * value all the same (§5.10).
     */
    @Test
    void aPinOfAParameterOnAReferenceTypedFieldIsRefused() {
        List<Diagnostic> problems = vocabularyProblems(
                "my_set => <T> meta.array ^ { element_type?: = T  ordered?: = false  unique_items?: = true }");

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("parameter 'T' stands at the value of field 'element_type'"),
                problems::toString);
    }
}
