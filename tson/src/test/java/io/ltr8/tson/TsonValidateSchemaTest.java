package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Tson#validateSchema}, the schema-side peer of {@link Tson#validate} -- and the first caller that
 * actually composes resolution and linking with a receiver, so it is where the phase boundary is enforced
 * rather than merely documented.
 */
class TsonValidateSchemaTest {

    private static final String HEADER = """
            !!id:"https://example.test/validate-schema-test.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            """;

    private static List<Diagnostic> check(String body) {
        return Tson.builder().build().validateSchema(HEADER + body);
    }

    @Test
    void aSoundSchemaReportsNothing() {
        assertEquals(List.of(), check("""
                {
                  my_int => int32
                  my_record => { value: int32 }
                }
                """));
    }

    @Test
    void everyBrokenDeclarationIsReportedInOnePass() {
        List<Diagnostic> problems = check("""
                {
                  widens => !uint8 ^ { min: -10 }
                  fine => int32
                  declared_twice => { value: int32  value: int32 }
                  typo_in_source => !no_such_atom ^ { max: 1 }
                }
                """);

        assertEquals(List.of("/declared_twice", "/typo_in_source", "/widens"),
                problems.stream().map(Diagnostic::schemaPointer).sorted().toList());
    }

    /**
     * The phase boundary. {@code widens} fails to resolve and {@code dangling} has an unresolved reference,
     * which is a *linking* problem -- only the resolution failure is reported, because a reference may well
     * resolve once the declaration it points at does, so reporting it now would be reporting a possible
     * consequence of the first error as though it were independent.
     */
    @Test
    void linkingDoesNotRunWhenResolutionReportedSomething() {
        List<Diagnostic> problems = check("""
                {
                  widens => !uint8 ^ { min: -10 }
                  dangling => { x: no_such_type }
                }
                """);

        assertEquals(List.of("/widens"), problems.stream().map(Diagnostic::schemaPointer).toList());
    }

    /** With resolution clean, linking runs and reports its own problems -- both of them. */
    @Test
    void linkingProblemsAreReportedWhenResolutionIsClean() {
        List<Diagnostic> problems = check("""
                {
                  a => { x: no_such_type }
                  b => { y: also_missing }
                  fine => int32
                }
                """);

        assertEquals(List.of("/a", "/b"), problems.stream().map(Diagnostic::schemaPointer).sorted().toList());
    }

    /** Every schema diagnostic names its schema and where in the source it is (§8.1's MUST). */
    @Test
    void diagnosticsCarryTheSchemaIdentityAndPosition() {
        for (Diagnostic problem : check("{ widens => !uint8 ^ { min: -10 } }")) {
            assertEquals("example.test/validate-schema-test.tn", problem.schemaId());
            assertTrue(problem.schemaPosition().isPresent());
            assertEquals("", problem.path(), "a schema problem has no data location");
        }
    }

    /** Never throws for a bad input document -- malformed syntax comes back as a diagnostic like anything else. */
    @Test
    void malformedSyntaxIsADiagnosticNotAnException() {
        List<Diagnostic> problems = Tson.builder().build().validateSchema(HEADER + "{ oops => ");

        assertFalse(problems.isEmpty());
    }

    /** A document-level problem carries RFC 6901's root pointer rather than naming a declaration. */
    @Test
    void anUnloadableImportIsReportedAgainstTheDocument() {
        List<Diagnostic> problems = Tson.builder().build().validateSchema("""
                !!id:"https://example.test/bad-import.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://example.test/nothing-here.tn"
                { my_int => int32 }
                """);

        assertFalse(problems.isEmpty());
        assertEquals("", problems.get(0).schemaPointer());
    }

    /** A schema that failed is not registered, so a later call can't find a half-resolved entry from it. */
    @Test
    void aFailedSchemaIsNotRegistered() {
        Tson tson = Tson.builder().build();

        assertFalse(tson.validateSchema(HEADER + "{ widens => !uint8 ^ { min: -10 } }").isEmpty());
        assertTrue(tson.schemaRegistry().get("https://example.test/validate-schema-test.tn").isEmpty());
    }
}
