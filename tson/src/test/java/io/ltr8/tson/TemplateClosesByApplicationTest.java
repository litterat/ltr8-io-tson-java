package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;

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
 * <p><b>It used to be an {@code IllegalStateException}</b> -- this project's spelling of "an internal
 * invariant broke" -- so `tson validate` printed the please-report-it banner and a stack trace and exited
 * <b>70</b> for a mistake in someone's schema. The throw is still in {@code DefinitionResolver} and its own
 * comment claimed it was unreachable from anything the parser produces; what made that false is that an
 * <em>open</em> declaration holds its body rather than resolving it to a record, and a parameterised {@code
 * ~} declaration is exactly one.
 *
 * <p>End to end through {@link Tson} rather than at {@code DefinitionResolver}, because the construction
 * head resolves against the <em>structure</em> namespace -- which needs a real governing meta-schema, and
 * because what is being asserted is the user-visible half: the problem arrives as a collected {@link
 * Diagnostic}, not as an exception past the collector.
 */
class TemplateClosesByApplicationTest {

    private static final String META = "https://example.test/m.tn";
    private static final String USER = "https://example.test/u.tn";

    /**
     * Three templates over one governing meta-schema: a {@code ~} constructor whose parameter routes into a
     * <b>value</b> slot (§4.2's legal shape), one whose parameter routes into a <b>type</b> slot, and an
     * unmarked §5.10 template. All three are templates, which is the only thing the rule turns on.
     */
    private static String meta(String extraDeclarations) {
        return """
                !!id:"https://example.test/m.tn"
                !!meta:"https://tson.io/2026/35/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/35/m/meta-kernel.tn"
                {
                  base       => {}
                  my_bounded => <N> ~array ^ { max_items: = N }
                  my_set     => <T> ~array ^ { element_type: = T  unordered: = true  unique_items: = true }
                  box        => <T> base & { value: T }
                  %s
                }
                """.formatted(extraDeclarations);
    }

    private static final String META_SOURCE = meta("");

    /**
     * A template <em>application</em> resolves in the type-name namespace, which {@code !!meta} does not
     * contribute (§3.3.3) -- so the closing cases are declared in the meta-schema beside the templates, where
     * a construction site needs only the structure namespace the governing meta already is.
     */
    private static List<Diagnostic> metaProblems(String extraDeclarations) {
        String source = meta(extraDeclarations);
        Tson tson = Tson.builder()
                .schemaSource(TsonSchemaSource.ofMap(Map.of(META, source)))
                .build();
        return tson.validateSchema(source);
    }

    private static List<Diagnostic> schemaProblems(String body) {
        String user = """
                !!id:"https://example.test/u.tn"
                !!meta:"https://example.test/m.tn"
                { %s }
                """.formatted(body);
        Tson tson = Tson.builder()
                .schemaSource(TsonSchemaSource.ofMap(Map.of(META, META_SOURCE, USER, user)))
                .build();
        return tson.validateSchema(user);
    }

    /** The meta-schema itself is fine: declaring a template is not the mistake, applying one wrongly is. */
    @Test
    void theTemplatesThemselvesResolve() {
        Tson tson = Tson.builder()
                .schemaSource(TsonSchemaSource.ofMap(Map.of(META, META_SOURCE)))
                .build();

        assertEquals(List.of(), tson.validateSchema(META_SOURCE));
    }

    @Test
    void aConstructorTemplateAtAConstructionSiteIsAnAuthorError() {
        List<Diagnostic> problems = schemaProblems("n => !my_bounded { max_items: 3 }");

        assertEquals(1, problems.size(), problems::toString);
        Diagnostic only = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, only.code());
        assertTrue(only.message().contains("is a template taking 1 type argument [N]"), only::toString);
        assertTrue(only.message().contains("my_bounded<...>"), only::toString);
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

    /**
     * §4.2's <b>value-route-only</b> rule, from the working end: a parameter routed into a slot typed by an
     * atom closes by application, so the check cannot simply refuse every parameterised {@code ~}
     * declaration.
     */
    @Test
    void aValueRoutedConstructorParameterClosesByApplication() {
        assertEquals(List.of(), metaProblems("three => my_bounded<3>"));
    }

    /**
     * And the type-channel spelling is refused on closing -- by §5.2 rather than by §4.2's own rule: {@code
     * element_type} is typed {@code type_ref}, which is a record, and a fixed value is available on a field
     * typed by an atom or an enum and nowhere else. So §4.2's rule is enforced, at the point the argument
     * lands, by the rule that owns field values.
     */
    @Test
    void aTypeChannelConstructorParameterIsRefusedWhenItCloses() {
        List<Diagnostic> problems = metaProblems("tags => my_set<text>");

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("cannot have a fixed value"), problems::toString);
    }
}
