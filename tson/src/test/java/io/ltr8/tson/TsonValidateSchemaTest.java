package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
                problems.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
    }

    /**
     * An invalid sugar form is one declaration's problem, and reads as one. Desugaring runs inside
     * {@code resolveSchema}, so its throw used to land in this method's document-level catch and be reported
     * at RFC 6901's root pointer with no declaration name and no position -- one mislocated diagnostic
     * standing in for however many problems the schema actually had. Both halves are pinned here: the sugar
     * form is attributed to {@code vacuous}, and the unrelated {@code widens} is reported alongside it rather
     * than never being reached.
     */
    @Test
    void anInvalidSugarFormNamesItsDeclarationAndDoesNotHideTheRest() {
        List<Diagnostic> problems = check("""
                {
                  vacuous => [int32; 0..]
                  widens => !uint8 ^ { min: -10 }
                  fine => int32
                }
                """);

        assertEquals(List.of("/vacuous", "/widens"),
                problems.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
        Diagnostic sugar = problems.stream().filter(d -> d.schemaPointer().equals(Optional.of("/vacuous")))
                .findFirst().orElseThrow();
        assertEquals("example.test/validate-schema-test.tn", sugar.schemaId());
        assertTrue(sugar.schemaPosition().isPresent(), "a sugar-form error carries the declaration's position");
        assertTrue(sugar.message().contains("pins a floor of zero"), sugar.message());
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

        assertEquals(List.of("/widens"), problems.stream().map(d -> d.schemaPointer().orElseThrow()).toList());
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

        assertEquals(List.of("/a", "/b"), problems.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
    }

    /** Every schema diagnostic names its schema and where in the source it is (§8.1's MUST). */
    @Test
    void diagnosticsCarryTheSchemaIdentityAndPosition() {
        for (Diagnostic problem : check("{ widens => !uint8 ^ { min: -10 } }")) {
            assertEquals("example.test/validate-schema-test.tn", problem.schemaId());
            assertTrue(problem.schemaPosition().isPresent());
            assertEquals(Optional.empty(), problem.path(), "a schema problem has no data location");
        }
    }

    /** Never throws for a bad input document -- malformed syntax comes back as a diagnostic like anything else. */
    @Test
    void malformedSyntaxIsADiagnosticNotAnException() {
        List<Diagnostic> problems = Tson.builder().build().validateSchema(HEADER + "{ oops => ");

        assertFalse(problems.isEmpty());
    }

    /** Parsing is a reporting phase like resolution: every declaration's syntax error, in one pass. */
    @Test
    void everySyntaxErrorIsReportedInOnePass() {
        List<Diagnostic> problems = check("""
                {
                  first => { x: }
                  fine => int32
                  second => { quantity: !int32 ^ { min: 1 } }
                }
                """);

        assertEquals(List.of("/first", "/second"),
                problems.stream().map(d -> d.schemaPointer().orElseThrow()).toList());
        assertEquals("example.test/validate-schema-test.tn", problems.get(0).schemaId());
        assertTrue(problems.get(0).schemaPosition().isPresent(), "a schema syntax error locates itself in the schema");
        assertEquals(Optional.empty(), problems.get(0).path(), "a schema document is not data");
    }

    /**
     * A syntax error stops the pipeline before resolution, so the unresolved reference the *broken*
     * declaration would have caused is not reported on top of it. {@code fine} references {@code missing},
     * which nothing declares -- a real resolution error, and one an author cannot act on until the syntax is
     * fixed, since the fix may well declare it.
     */
    @Test
    void aSyntaxErrorStopsThePipelineBeforeResolutionSpeaks() {
        List<Diagnostic> problems = check("""
                {
                  broken => { x: }
                  fine => { y: missing }
                }
                """);

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.get(0).code());
    }

    /** An inline atom refinement is the one syntax error worth naming the fix for, not just the token. */
    @Test
    void anInlineAtomRefinementIsToldHowToBecomeADeclaration() {
        List<Diagnostic> problems = check("{ order => { quantity: !int32 ^ { min: 1 } } }");

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).message().contains("declare a named type instead"),
                problems.get(0)::message);
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
        assertEquals(Optional.of(""), problems.get(0).schemaPointer());
    }

    /**
     * An {@code !!import} whose target carries a different {@code !!id} than the reference it was fetched
     * under ([TSON-DATA] §2.2.1's cross-check) is an authoring/publishing error, so it reports like one
     * rather than escaping as a library fault -- the CLI keeps exit 1 and exit 70 apart on exactly that
     * distinction, and {@link Tson#validateSchema} promises never to throw for a bad document.
     */
    @Test
    void anImportWhoseTargetOwnsAnotherIdentityIsReportedAgainstTheDocument() {
        String lib = """
                !!id:"https://example.test/its-real-name.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                { widget => { name: text } }
                """;
        Tson tson = Tson.builder()
                .schemaSource(uri -> {
                    if (uri.equals("https://example.test/fetched-as.tn")) {
                        return lib;
                    }
                    throw new IllegalStateException("no schema for " + uri);
                })
                .build();

        List<Diagnostic> problems = tson.validateSchema("""
                !!id:"https://example.test/mismatched-import.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://example.test/fetched-as.tn"
                { holder => { w: widget } }
                """);

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Optional.of(""), problems.getFirst().schemaPointer());
        assertTrue(problems.getFirst().message().contains("identity mismatch"), problems::toString);
    }

    /** A schema that failed is not registered, so a later call can't find a half-resolved entry from it. */
    @Test
    void aFailedSchemaIsNotRegistered() {
        Tson tson = Tson.builder().build();

        assertFalse(tson.validateSchema(HEADER + "{ widens => !uint8 ^ { min: -10 } }").isEmpty());
        assertTrue(tson.schemaRegistry().get("https://example.test/validate-schema-test.tn").isEmpty());
    }
}
