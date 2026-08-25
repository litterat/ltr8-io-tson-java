package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forward-reference resolution (§3.4.1): a declaration may compose/refine one declared later in the same
 * schema (resolution now follows dependencies, not source order). Recursion through <i>field</i> references
 * — which carry a bare name, verified later by the linker — resolves fine; a circular <i>composition</i>
 * chain, which genuinely needs the target resolved to resolve the dependant, is rejected.
 */
class ForwardReferenceResolutionTest {

    private static TsonSchema resolve(String body) {
        String document = """
                !!id:"https://example.test/fwd.tn"
                !!meta:"https://tson.io/2026/33/m/meta-kernel.tn"
                {
                """ + body + "\n}\n";
        SchemaResolver resolver = new SchemaResolver(new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext()));
        return resolver.resolveSchema(new TsonSchemaParser(document).parseSchemaDocument());
    }

    @Test
    void composesASupertypeDeclaredLaterInTheSameSchema() {
        // employee is declared *before* person, the supertype it composes -- fails under strict source order.
        TsonSchema resolved = resolve("  employee => person & { }\n  person => { }");

        TypeDefinition employee = resolved.entries().get("employee");
        assertNotNull(employee);
        assertTrue(employee.supertypes().contains("person"),
                () -> "employee should compose person; supertypes=" + employee.supertypes());
    }

    @Test
    void mutuallyRecursiveRecordsViaFieldReferencesResolve() {
        // x and y reference each other by field type (a bare name) -- valid recursion, not a resolution cycle.
        TsonSchema resolved = resolve("  x => { y: y }\n  y => { x: x }");

        assertNotNull(resolved.entries().get("x"));
        assertNotNull(resolved.entries().get("y"));
    }

    @Test
    void aSelfRecursiveRecordResolvesWithoutFalseTrippingCycleDetection() {
        // item references itself by field type -- a bare name, so it never enters the resolving set the way a
        // self-composition would. Resolves fine. (Unsatisfiable with a REQUIRED field -- no finite value --
        // which resolution deliberately doesn't police; see SPEC-FEEDBACK.md #25.)
        TsonSchema resolved = resolve("  item => { inner: item }");

        assertNotNull(resolved.entries().get("item"));
    }

    @Test
    void aCircularCompositionChainIsRejected() {
        // a composes b composes a -- resolution needs each other's resolved form, so neither can complete.
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolve("  a => b & { }\n  b => a & { }"));

        assertTrue(thrown.getMessage().contains("circular"), thrown::getMessage);
    }

    @Test
    void aCircularCompositionChainWithFieldBodiesIsAlsoRejected() {
        // Same cycle, now with tightening-body fields -- the cycle is in the composition (resolved before
        // the fields), so a field body provides no escape and it's caught the same way.
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolve("  a => b & { p: text }\n  b => a & { q: text }"));

        assertTrue(thrown.getMessage().contains("circular"), thrown::getMessage);
    }
}
