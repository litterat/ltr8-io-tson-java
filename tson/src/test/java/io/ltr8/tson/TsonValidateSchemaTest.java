package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.meta.SourcePosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            """;

    private static List<Diagnostic> check(String body) {
        return Tson.builder().build().validateSchema(HEADER + body);
    }

    /**
     * <b>An {@code !!import} nobody would serve is a fetch failure with a stated reason.</b> This is the
     * schema-document channel of the same problem a read hits, and it must answer the same way: {@code
     * SCHEMA_UNAVAILABLE} for "no schema was obtained", and {@link TsonSchemaFetchException.Reason} for
     * whose doing that was -- {@code NOT_PERMITTED} for a reference this deployment refuses being the
     * author's to fix, where {@code TIMEOUT} is not. The {@code actual} half names the reference itself,
     * since that is what the author would go and look at.
     */
    @Test
    void anUnfetchableImportStatesWhyItCouldNotBeFetched() {
        TsonSchemaSource refusing = uri -> {
            throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_PERMITTED,
                    "not an allowed host", null);
        };
        List<Diagnostic> problems = Tson.builder().schemaSource(refusing).build().validateSchema("""
                !!id:"https://example.test/importer.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://example.test/nowhere.tn"
                { t => text }
                """);

        assertEquals(1, problems.size(), problems::toString);
        Diagnostic problem = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_NOT_PERMITTED, problem.code());
        assertEquals(Optional.of("https://example.test/nowhere.tn"), problem.actualIfStated());
    }

    /**
     * <b>A parameter inside a collection-valued slot is ordinary</b> (§5.10). An open entry's body is held,
     * uninterpreted until materialisation substitutes, so a parameter in {@code variants} or {@code elements}
     * is a token inside an array and the phase that would have had to classify it does not run --
     * "collection-valued slots are parameterizable", with {@code result => <T> ( T | error )} as the
     * spec's own example and this test's flagship case.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{ result => <T> ( T | error )  use => result<text>  error => { code: text } }",  // §5.4 variants
            "{ box => <T> { v: (T | text) }  use => box<int32> }",                            // ... nested
            "{ pair => <T> [T, text]  use => pair<int32> }"})                                 // tuple elements
    void aParameterInACollectionSlotIsOrdinary(String body) {
        assertEquals(List.of(), check(body));
    }

    /**
     * A failure inside a <b>derived</b> entry is reported against the declaration that caused it, and names
     * the form rather than the entry. Neither half was true: {@code use => { u: [some_typo] }} reported
     * against {@code array_some_typo_95c9a10f} -- a content-derived name §8.2 makes non-normative, that the
     * author never wrote -- and carried no position at all, while the same mistake spelled {@code u:
     * some_typo} landed on {@code /use} with its own line. The two now agree.
     *
     * <p>Nothing here involves a template: every sugar form lifts an entry, so this was every schema's
     * problem and not the open form's.
     */
    @Test
    void aFailureInsideALiftedFormIsReportedAgainstTheDeclarationThatWroteIt() {
        List<Diagnostic> lifted = check("{ use => { u: [some_typo] } }");
        List<Diagnostic> plain = check("{ use => { u: some_typo } }");

        assertEquals(1, lifted.size(), lifted::toString);
        assertEquals(plain.getFirst().schemaPointer(), lifted.getFirst().schemaPointer(), "same declaration");
        assertEquals(plain.getFirst().schemaPosition().map(SourcePosition::line),
                lifted.getFirst().schemaPosition().map(SourcePosition::line), "and the same line");
        assertTrue(lifted.getFirst().message().contains("'[some_typo]'"),
                () -> "named by the form the author wrote: " + lifted.getFirst().message());
    }

    /**
     * <b>A defect a held body deferred belongs to the declaration whose text wrote it, not to whoever applied
     * the template.</b> Nothing checks {@code box}'s own references at its declaration -- a template's
     * references cannot be settled until an application supplies arguments -- so the verdict arrives on the
     * entry {@code box<text>} minted, and the walk back to a positioned entry finds {@code holder}. That line
     * is not wrong and does not contain the name. Deferred checking is what holding buys, and it is
     * survivable only if the author is sent to the line they can edit.
     *
     * <p><b>The subject moves with the location</b>, which the two halves of this assert together: naming
     * {@code 'box<text>'} states the mistake against an application that is itself correct, and would name a
     * different one for every applier.
     */
    @Test
    void aDefectInsideATemplateIsReportedAgainstTheTemplate() {
        List<Diagnostic> problems = check("""
                {
                  box => <T> { v: T  w: no_such_type }
                  holder => { b: box<text> }
                }""");

        assertEquals(1, problems.size(), problems::toString);
        Diagnostic only = problems.getFirst();
        assertEquals(Optional.of("/box"), only.schemaPointer());
        assertEquals(Optional.of(5), only.schemaPosition().map(SourcePosition::line));
        assertEquals("'box' field 'w' has an unresolved reference 'no_such_type'", only.message());
    }

    /**
     * Every template shape holds its body, so every one of them defers the same way -- and the trail inside
     * the message ({@code field 'w'}, {@code element[1]}, {@code variant[1]}) transfers to the declaration
     * unchanged, a closed body being the template's own with its parameters replaced.
     *
     * <p>The <b>alias</b> case is the one that decides how this is implemented. {@code half<text>} composes
     * to {@code pair<no_such_type, text>} and mints an entry sourced on {@code pair} -- so walking a derived
     * entry's lineage lands on {@code pair}, which is faultless. The offending <em>name</em> is the evidence
     * instead: {@code half} is the held body that mentions it.
     */
    @Test
    void anOpenTupleTemplateIsBlamedForItsOwnHeldText() {
        assertBlamed("boxes => <T> [T, no_such_type]", "/boxes",
                "'boxes' element[1] has an unresolved reference 'no_such_type'");
    }

    @Test
    void anOpenChoiceTemplateIsBlamedForItsOwnHeldText() {
        assertBlamed("result => <T> ( T | no_such_type )", "/result",
                "'result' variant[1] has an unresolved reference 'no_such_type'");
    }

    @Test
    void aTemplateAppliedByAnotherTemplateIsBlamedForItsOwnHeldText() {
        assertBlamed("inner => <T> { i: T  bad: no_such_type }", "/inner",
                "'inner' field 'bad' has an unresolved reference 'no_such_type'");
    }

    /** One open declaration, applied once by a {@code holder} that is itself correct. */
    private static void assertBlamed(String declaration, String pointer, String message) {
        String name = declaration.substring(0, declaration.indexOf(' '));
        List<Diagnostic> problems = check("{\n  " + declaration + "\n  holder => { b: " + name + "<text> }\n}");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Optional.of(pointer), problems.getFirst().schemaPointer());
        assertEquals(Optional.of(5), problems.getFirst().schemaPosition().map(SourcePosition::line));
        assertEquals(message, problems.getFirst().message());
    }

    /** §5.10's partial application: the alias wrote the name, and the application it composes into did not. */
    @Test
    void anAliasIsBlamedForAnArgumentItSuppliesItself() {
        List<Diagnostic> problems = check("""
                {
                  pair => <A, B> { first: A  second: B }
                  half => <B> pair<no_such_type, B>
                  holder => { b: half<text> }
                }""");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Optional.of("/half"), problems.getFirst().schemaPointer());
        assertEquals(Optional.of(6), problems.getFirst().schemaPosition().map(SourcePosition::line));
    }

    /**
     * One mistake in a template is one mistake however many declarations apply it. Each application mints its
     * own entry and each fails identically, so without this the author gets the same sentence once per
     * applier -- and the count is a property of the schema's callers rather than of the defect.
     */
    @Test
    void aTemplateDefectIsReportedOncePerDefectNotOncePerApplication() {
        List<Diagnostic> problems = check("""
                {
                  box => <T> { v: T  w: no_such_type }
                  first => { b: box<text> }
                  second => { b: box<int32> }
                  third => { b: box<float32> }
                }""");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Optional.of("/box"), problems.getFirst().schemaPointer());
    }

    /**
     * <b>The converse, and the reason the offending name decides this rather than the entry.</b> A name the
     * <em>applier</em> wrote is the applier's mistake, so this stays exactly where it was written: no held
     * body mentions {@code 3}, and nothing is retargeted. Blaming the template would send the author to
     * {@code box => <T> { v: T }} to look for a {@code 3} that is not there.
     *
     * <p>The literal case is refused for a sharper reason than the typo in {@link
     * #anUnresolvedArgumentStaysWithTheApplier}: {@code 3} is not an unresolved <em>name</em>, it is not a
     * name at all, and {@code type_ref.name} is typed {@code identifier}, so it fails where the substituted
     * body is read against the kernel's own vocabulary rather than later at reference resolution. The verdict
     * is the same and the explanation is better — "an identifier never begins with a digit or a sign" is
     * actionable where "unresolved reference '3'" implies an author could go and declare one.
     */
    @Test
    void aLiteralArgumentInATypeSlotStaysWithTheApplier() {
        assertStaysWithTheApplier("box<3>",
                "'box<...>' substitutes into a body that is not valid data for 'record', the constructor's "
                        + "own constraint vocabulary -- 'type_name': '3': U+0033 at index 0 cannot start an "
                        + "identifier -- an identifier never begins with a digit or a sign");
    }

    /** The same rule for a name that is a name: {@code some_typo} is the applier's typo, not the template's. */
    @Test
    void anUnresolvedArgumentStaysWithTheApplier() {
        assertStaysWithTheApplier("box<some_typo>",
                "'box<some_typo>' source has an unresolved reference 'some_typo'");
    }

    private static void assertStaysWithTheApplier(String application, String message) {
        List<Diagnostic> problems =
                check("{\n  box => <T> { v: T }\n  holder => { b: " + application + " }\n}");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Optional.of("/holder"), problems.getFirst().schemaPointer());
        assertEquals(Optional.of(6), problems.getFirst().schemaPosition().map(SourcePosition::line));
        assertEquals(message, problems.getFirst().message());
    }

    /**
     * A closed declaration's own typo is never handed to a template, however the name reads there. {@code
     * TemplateBody#names()} cannot tell a type reference from a field name, so a template with a field called
     * {@code no_such_type} matches the name -- and must not be blamed, because the failing entry here is the
     * author's own declaration and already carries the line they wrote.
     */
    @Test
    void aClosedDeclarationKeepsItsOwnTypoEvenWhenATemplateNamesIt() {
        List<Diagnostic> problems = check("""
                {
                  box => <T> { no_such_type: T }
                  use => { u: no_such_type }
                  holder => { b: box<text> }
                }""");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Optional.of("/use"), problems.getFirst().schemaPointer());
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

    /**
     * Every broken declaration gets its own verdict in one pass, rather than the first aborting the document.
     *
     * <p><b>This now carries what a gap fixture used to demonstrate beside it, because no schema-side gap is
     * reachable any more.</b> A construct this library has not implemented is reported and not thrown, so the
     * declarations around it still get their verdicts, and the code is what keeps the two apart: {@code
     * NOT_IMPLEMENTED} says this could not be checked where {@code SCHEMA_ERROR} says this is wrong -- a
     * consumer conflating them is wrong in the direction that matters, calling a document invalid that was
     * never judged. Three fixtures held that in turn and each was closed by the work that followed it: a
     * parameter in a collection-valued slot (the author's error now), a parameterized supertype, and one
     * template applied to another. The machinery is untouched -- {@code SchemaResolver} still routes an
     * {@code UnsupportedOperationException} through the receiver, in the catch inside its memoized getter --
     * so the day a schema-side gap reappears this should become a fixture again.
     *
     * <p><b>The read side does have reachable gaps, and they cannot serve as a fixture here</b>: a read gap
     * escapes as an exception instead of reaching a receiver, so it never becomes a {@code Diagnostic} at
     * all. {@code TsonCliTest} pins both halves end to end -- that such a run exits 70 with the right
     * framing, and that it currently loses every other document's verdict on the way.
     */
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
     * A broken template declaration is one problem, and its applications are not further problems. The
     * placeholder a failed declaration leaves behind keeps that declaration's own type parameters, so
     * {@code bl<int32>} arity-checks against it and closes silently; with the parameter list dropped, the
     * application was told that {@code bl} "declares no type parameters ... drop the argument list" -- the
     * one piece of advice guaranteed to be wrong, since following it breaks the schema further while the
     * real fix is upstream. Both placeholders are pinned, the desugarer's and the resolver's, because a
     * template can fail at either phase.
     */
    @Test
    void anApplicationOfABrokenTemplateAddsNoDiagnosticOfItsOwn() {
        for (String broken : List.of("bl => <T> { v: T  v: T }", "bl => <T> [T; 0..]")) {
            List<Diagnostic> problems = check("""
                    {
                      %s
                      use => { b: bl<int32> }
                    }
                    """.formatted(broken));

            assertEquals(List.of("/bl"),
                    problems.stream().map(d -> d.schemaPointer().orElseThrow()).toList(), broken);
        }
    }

    /**
     * A regularity violation is reported where it is declared, once. The condemned template is replaced
     * before materialisation, so nothing goes on to apply it: left in place, an application of it ran to
     * the 64-deep backstop and reported the same defect a second time -- against the entry that applied it,
     * carrying a chain of synthetic names the author never wrote. The depth guard itself stays exactly as
     * it is; what it guards against is a hole in this check, not a template this check already condemned.
     */
    @Test
    void anIrregularTemplateIsReportedAtItsDeclarationAndNotAgainAtItsApplication() {
        List<Diagnostic> problems = check("""
                {
                  box => <T> { v: T }
                  weird => <T> { next: weird<box<T>>? }
                  use => { w: weird<int32> }
                }
                """);

        assertEquals(List.of("/weird"), problems.stream().map(d -> d.schemaPointer().orElseThrow()).toList());
        assertTrue(problems.get(0).message().contains("does not pass 'T' through unchanged"),
                problems.get(0).message());
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
                !!meta:"https://tson.io/2026/34/m/meta.tn"
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
                !!meta:"https://tson.io/2026/34/m/meta.tn"
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
                !!meta:"https://tson.io/2026/34/m/meta.tn"
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
