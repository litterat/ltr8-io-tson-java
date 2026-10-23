package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A field typed {@code ref<T>} in an ordinary schema, reached through core.tn's own {@code ref} as {@code kind} is: an
 * ordinary schema imports no meta-kernel (§2.2.3), so core's sibling is the one route by which its field names a
 * value. The schema validates; a data document's read of the reference is this library's gap, reported as {@link
 * Diagnostic.Code#NOT_IMPLEMENTED} in both modes, never silence and never a schema error.
 */
class ReferenceInDataDocumentTest {

    private static final String ID = "https://example.test/ref-in-data.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/ref-in-data.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              order => { id: text }
              pick  => { m: ref<order> }
            }""";

    public record Order(String id) {
    }

    public record Pick(String m) {
    }

    private static Tson tson() {
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("order", Order.class, "pick", Pick.class)))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        return Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, SCHEMA))))
                .withDataBindContext(context));
    }

    @Test
    void anOrdinarySchemaTypesAFieldByCoresRef() {
        assertEquals(List.of(), Tson.standard().validateSchema(SCHEMA));
    }

    @Test
    void aReferenceInADataDocumentIsTheGapInTreeMode() {
        List<Diagnostic> problems = tson().validate("!!schema:\"" + ID + "\"\n!pick { m: order }");

        assertFalse(problems.isEmpty(), "a reference in a data document is not read, and the read says so");
        problems.forEach(problem -> assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, problem.code(), problem.toString()));
    }

    @Test
    void aReferenceInADataDocumentIsTheGapInBindMode() {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        try {
            tson().objectReader().withDiagnostics(problems).withSchema(ID).readAs("!pick { m: order }", "pick",
                    Pick.class);
        } catch (RuntimeException ignored) {
            // what was reported is the verdict; a read that cannot bind may also end by throwing
        }

        assertFalse(problems.diagnostics().isEmpty(), "a reference in a data document is not read, and the read "
                + "says so");
        problems.diagnostics().forEach(problem -> assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, problem.code(),
                problem.toString()));
    }

    /**
     * A constructor's types are named by a kind: core's {@code ref} refuses one as the kernel's does, though an
     * ordinary schema's own namespace holds no {@code record} to resolve it to.
     */
    @Test
    void aReferenceToAConstructorIsRefused() {
        List<Diagnostic> problems = Tson.standard().validateSchema("""
                !!id:"https://example.test/ref-to-constructor.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  pick => { m: ref<record> }
                }""");

        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(problem -> problem.message().contains("kind<record>")),
                problems.toString());
    }
}
