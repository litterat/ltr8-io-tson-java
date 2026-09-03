package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonContentHash;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Tson#validate} works out on its own whether a data document's {@code !!schema} selects a
 * schema (resolved through the configured {@link TsonSchemaSource}, type from the root type-ref) or
 * whether it's validated schemalessly, returning every problem as a {@link Diagnostic} (empty == valid).
 */
class TsonValidateTest {

    private static final String POINT_ID = "https://example.test/point-1.tn";
    private static final String POINT_SCHEMA = """
            !!id:"https://example.test/point-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            { point => { x: int32  y: int32 } }
            """;

    private static Tson tsonWithPoint() {
        TsonSchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;   // ignore any ?sha256= pin
            if (base.equals(POINT_ID)) {
                return POINT_SCHEMA;
            }
            throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_FOUND,
                    "this fixture serves only " + POINT_ID, null);
        };
        return Tson.builder().schemaSource(source).build();
    }

    @Test
    void selfDescribingDataResolvesItsSchemaAndValidates() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4 }""");
        assertEquals(List.of(), problems);
    }

    @Test
    void aBadValueInSchemaDrivenDataIsReported() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 99999999999999 }""");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
    }

    /**
     * A base-syntax failure keeps the company it was found in. The stream is lazy, so the parse error
     * surfaces after the value problem before it -- and both belong to one document, so both are returned.
     * This used to discard the collector and answer with the syntax error alone.
     */
    @Test
    void aDocumentWithAValueProblemAndASyntaxProblemReportsBoth() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 99999999999999  y: ,, }""");

        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, Diagnostic.Code.VALIDATION_ERROR),
                problems.stream().map(Diagnostic::code).toList(), problems.toString());
    }

    /**
     * <b>Not {@code SCHEMA_ERROR}</b>: nothing here has seen the schema, so nothing here can say it is
     * wrong. The document may be perfect and the schema may be perfect -- no source would supply it.
     */
    @Test
    void aSchemaTheSourceCannotProvideIsUnavailableRatherThanWrong() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/not-there.tn"
                !point { x: 3  y: 4 }""");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.SCHEMA_NOT_FOUND, problems.getFirst().code());
    }

    @Test
    void aSchemaDrivenDocumentWithNoRootTypeRefIsReported() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                { x: 3  y: 4 }""");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("root type-ref"), problems.toString());
    }

    @Test
    void aRootTypeRefTheSchemaDoesNotDeclareIsAnUnknownType() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                !no_such_type { x: 3  y: 4 }""");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problems.getFirst().code());
    }

    @Test
    void anUnknownRootTypeNamesWhatTheSchemaDoesDeclare() {
        // The root-position analogue of UNRECOGNIZED_FIELD: the prose suggests the nearest declared name and
        // `expected` carries the whole closed set, including the ~47 core.tn entries the !!import flattens
        // in -- which is why the prose lists only a few of them rather than all.
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                !pont { x: 3  y: 4 }""");
        assertEquals(1, problems.size(), problems.toString());
        Diagnostic problem = problems.getFirst();
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problem.code());
        assertTrue(problem.message().contains("did you mean 'point'?"), problem.message());
        assertTrue(problem.message().contains("and 44 more"), problem.message());
        assertEquals("pont", problem.actual());
        assertTrue(problem.expected().endsWith("| point"), problem.expected());
        assertTrue(problem.expected().contains("int32"), problem.expected());
    }

    @Test
    void plainAndPinnedReferencesToOneSchemaBothResolve() {
        // Two references differing only by a ?sha256= pin are one identity: the schema resolves and
        // registers once, so both validate rather than the second double-registering. (Both use the
        // correct pin so verification passes -- mismatch is its own test below.)
        String hash = TsonContentHash.sha256(POINT_SCHEMA.getBytes(StandardCharsets.UTF_8));
        Tson tson = tsonWithPoint();

        assertEquals(List.of(), tson.validate("!!schema:\"" + POINT_ID + "\"\n!point { x: 1  y: 2 }"));
        assertEquals(List.of(), tson.validate(
                "!!schema:\"" + POINT_ID + "?sha256=" + hash + "\"\n!point { x: 3  y: 4 }"));
    }

    @Test
    void aCorrectlyPinnedReferenceVerifies() {
        String hash = TsonContentHash.sha256(POINT_SCHEMA.getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), tsonWithPoint().validate(
                "!!schema:\"" + POINT_ID + "?sha256=" + hash + "\"\n!point { x: 1  y: 2 }"));
    }

    @Test
    void aMisPinnedReferenceIsRejected() {
        List<Diagnostic> problems = tsonWithPoint().validate(
                "!!schema:\"" + POINT_ID + "?sha256=" + "a".repeat(64) + "\"\n!point { x: 1  y: 2 }");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("mismatch"), problems.toString());
    }

    @Test
    void aConflictingPinOnALaterReferenceToOneSchemaIsRejected() {
        // §10.2: verification is per identity. A first (plain) reference resolves the schema and
        // records its content hash; a later reference whose pin doesn't match that hash errors rather
        // than resolving to the already-registered instance -- even though the schema is cached.
        Tson tson = tsonWithPoint();
        assertEquals(List.of(), tson.validate("!!schema:\"" + POINT_ID + "\"\n!point { x: 1  y: 2 }"));

        List<Diagnostic> problems = tson.validate(
                "!!schema:\"" + POINT_ID + "?sha256=" + "a".repeat(64) + "\"\n!point { x: 3  y: 4 }");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("mismatch"), problems.toString());
    }

    @Test
    void aDocumentReturnedUnderTheWrongIdentityIsRejected() {
        // §2.2.1 cross-check: a source hands back the point schema (own !!id point-1.tn) for a data
        // file that references a different identity -- so the content doesn't own the identity it was
        // obtained under. Refuse it rather than resolve mismatched content.
        TsonSchemaSource wrongIdSource = uri -> POINT_SCHEMA;   // ignores uri; always returns point-1.tn
        Tson tson = Tson.builder().schemaSource(wrongIdSource).build();

        List<Diagnostic> problems = tson.validate(
                "!!schema:\"https://example.test/other-1.tn\"\n!point { x: 1  y: 2 }");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("identity mismatch"), problems.toString());
    }

    @Test
    void aRejectedFetchDoesNotPoisonTheCacheForALaterValidReference() {
        // §10.2 caching: a failed verification must record nothing. A flaky source returns tampered
        // bytes first (so a correctly-pinned reference is rejected), then the real schema -- the second
        // reference must still resolve, i.e. the first, rejected fetch left no stale content hash behind.
        String correctHash = TsonContentHash.sha256(POINT_SCHEMA.getBytes(StandardCharsets.UTF_8));
        String tampered = POINT_SCHEMA.replace("int32", "int64");   // same !!id, different body -> different hash
        AtomicInteger calls = new AtomicInteger();
        TsonSchemaSource flaky = uri -> calls.getAndIncrement() == 0 ? tampered : POINT_SCHEMA;
        Tson tson = Tson.builder().schemaSource(flaky).build();

        String pinnedData = "!!schema:\"" + POINT_ID + "?sha256=" + correctHash + "\"\n!point { x: 1  y: 2 }";

        List<Diagnostic> rejected = tson.validate(pinnedData);   // tampered content -> pin mismatch
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, rejected.getFirst().code(), rejected.toString());

        assertEquals(List.of(), tson.validate(pinnedData));       // real content now -> resolves cleanly
    }

    @Test
    void dataWithNoSchemaIsValidatedSchemalessly() {
        Tson tson = tsonWithPoint();
        assertEquals(List.of(), tson.validate("{ id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09  n: !int32 5 }"));

        List<Diagnostic> problems = tson.validate("{ n: !int32 twelve }");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
    }

    /**
     * Repeated calls share one compiled-schema cache (each {@code validate} builds a reader over the
     * instance's own {@code treeRegistry()}), so this is the guard on that sharing: a verdict must not
     * depend on what was validated before it, in either direction.
     */
    @Test
    void repeatedValidationsOnOneInstanceAreIndependentOfEachOther() {
        Tson tson = tsonWithPoint();
        String valid = "!!schema:\"" + POINT_ID + "\"\n!point { x: 1  y: 2 }";
        String invalid = "!!schema:\"" + POINT_ID + "\"\n!point { x: 1 }";

        assertEquals(List.of(), tson.validate(valid));
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, only(tson, invalid).code());
        assertEquals(List.of(), tson.validate(valid));            // an earlier failure left nothing behind
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, only(tson, invalid).code());
    }

    /**
     * A value error points at both ends: where the value is in the data, and where the rule it broke lives in
     * a schema. The second half is only non-empty because {@code Tson.resolve}/the loader pass declaration
     * positions through resolution -- {@code schemaPosition} was dead in production until they did.
     *
     * <p><b>The schema end is the author's own path, not the leaf the constraint came from.</b> {@code y}'s
     * type is {@code int32}, declared in core.tn, and naming <em>that</em> would send a reader to a file they
     * did not write, at a line past the end of the four-line schema their data named -- while never mentioning
     * the field they can actually edit. So the pointer is {@code /point/y} and the identity is point-1.tn's:
     * JSON Schema 2020-12 §12.3's {@code keywordLocation}, which likewise follows the validation path rather
     * than naming the dereferenced target. The constraint itself is not lost -- {@code message} names {@code
     * int32} and {@code expected} carries its bounds.
     */
    @Test
    void aValueErrorLocatesTheAuthorsOwnFieldNotTheLeafTypeItResolvesTo() {
        Diagnostic problem = only(tsonWithPoint(), """
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 99999999999999 }""");

        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problem.code());
        assertTrue(problem.dataPosition().isPresent(), "the value's own position in the data");
        assertEquals(Optional.of("/point/y"), problem.schemaPointer(), "the field the author declared");
        assertEquals("example.test/point-1.tn", problem.schemaId(), "the schema the data named");
        assertTrue(problem.schemaPosition().isPresent(), "where point is declared");
        assertTrue(problem.message().contains("int32"), problem.message());
    }

    /**
     * <b>The pointer crosses a declaration boundary, and the anchor follows it.</b> {@code city} is declared
     * by {@code address}, not by {@code person}, so the path keeps extending ({@code keywordLocation} crosses
     * a {@code $ref} the same way) while the identity and position re-anchor on the record that actually
     * declares the field the path now ends with. A single fixed anchor would send a reader to {@code person}'s
     * line for a field {@code person} does not declare.
     */
    @Test
    void aNestedRecordsFieldExtendsThePointerAndReanchorsThePosition() {
        Tson tson = Tson.builder().build();
        tson.resolve("""
                !!id:"https://example.test/nested-1.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  person => { home: address }

                  address => { city: text }
                }
                """);

        Diagnostic problem = only(tson, """
                !!schema:"https://example.test/nested-1.tn"
                !person { home: { } }""");

        assertEquals(Optional.of("/person/home/city"), problem.schemaPointer());
        assertEquals("example.test/nested-1.tn", problem.schemaId());
        assertEquals(7, problem.schemaPosition().orElseThrow().line(), "address's line, not person's");
    }

    /**
     * A read whose root is not a record has no enclosing declaration to anchor on, so the reader's own is
     * taken -- and for an imported type that is the schema which <em>declared</em> it, which is the whole
     * reason {@code TsonSchemaLinker} keeps each merged entry's origin. Nothing encloses {@code int32} here,
     * so nothing displaces core.tn.
     */
    @Test
    void aRootValueWithNoEnclosingRecordCarriesItsOwnDeclaringSchema() {
        Diagnostic problem = only(tsonWithPoint(), """
                !!schema:"https://example.test/point-1.tn"
                !int32 99999999999999""");

        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problem.code());
        assertEquals(Optional.of("/int32"), problem.schemaPointer());
        assertEquals("tson.io/2026/35/m/core.tn", problem.schemaId(), "where int32 is actually declared");
    }

    /**
     * A desugar-injected entry ({@code [text]}'s own {@code array_text_…}) is never what a diagnostic points
     * at: the author wrote {@code tags: [text]}, so {@code /tagged/tags} is the location, and the injected
     * name survives only in {@code message}. The enclosing record supplies the position, which the injected
     * entry itself has none of.
     */
    @Test
    void anInjectedEntryIsLocatedByTheFieldThatDesugaredIntoIt() {
        Tson tson = Tson.builder().build();
        tson.resolve("""
                !!id:"https://example.test/tags-1.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { tagged => { tags: [text] } }
                """);

        Diagnostic problem = only(tson, """
                !!schema:"https://example.test/tags-1.tn"
                !tagged { tags: { a: 1 } }""");

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problem.code());
        assertEquals(Optional.of("/tagged/tags"), problem.schemaPointer());
        assertEquals("example.test/tags-1.tn", problem.schemaId());
        assertEquals(4, problem.schemaPosition().orElseThrow().line(),
                "tagged's own line -- kept across the desugar rewrite its [text] field forces");
    }

    /**
     * An <em>unrecognized</em> field is a data step with no schema step: {@code /point/nope} names nothing, so
     * extending the schema pointer with it would invent a location that does not exist. The data path still
     * records where the stray name was written.
     */
    @Test
    void anUnrecognizedFieldDoesNotExtendTheSchemaPointer() {
        Diagnostic problem = only(tsonWithPoint(), """
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4  nope: 5 }""");

        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, problem.code());
        assertEquals(Optional.of("/nope"), problem.path(), "the stray name is real data");
        assertEquals(Optional.of("/point"), problem.schemaPointer(), "and names nothing in the schema");
    }

    // ── Diagnostic field quality ─────────────────────────────────────────

    /**
     * {@code expected} is the machine-parseable half a caller builds its own message from, so it carries the
     * <em>constraint that failed</em> -- naming the type instead says strictly less than {@code message}
     * already does, and leaves a consumer regexing the sentence to recover the bound.
     */
    @Test
    void aConstraintViolationCarriesTheViolatedBoundNotTheTypeName() {
        Diagnostic problem = only(tsonWithPoint(), """
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 99999999999999 }""");

        assertEquals(">= -2147483648 and <= 2147483647", problem.expected());
        assertEquals("99999999999999", problem.actual());
        assertTrue(problem.message().startsWith("'int32':"), problem.message());
    }

    /**
     * The declaring type still leads the <em>message</em>: {@code expected} gives up the name to carry the
     * bound, and the name is what its author wrote and can act on, so it must not vanish from the
     * diagnostic entirely. The <em>declaration's</em> name, not the built-in it refines.
     */
    @Test
    void aRefinementIsNamedByItsOwnDeclarationNotItsSource() {
        String schemaId = "https://example.test/pct-1.tn";
        String schema = """
                !!id:"https://example.test/pct-1.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  my_percentage => !positive_integer ^ { max: 100 }
                  reading => { pct: my_percentage }
                }
                """;
        Tson tson = Tson.builder().schemaSource(uri -> {
            if (uri.equals(schemaId)) {
                return schema;
            }
            throw new IllegalStateException("no schema for " + uri);
        }).build();

        Diagnostic problem = only(tson, "!!schema:\"" + schemaId + "\"\n!reading { pct: 500 }");

        assertEquals("<= 100", problem.expected());
        assertTrue(problem.message().startsWith("'my_percentage':"), problem.message());
    }

    /**
     * A facade-level failure carries a structured half too. These three are the ones that used to pass
     * {@code expected}/{@code actual} as {@code ""} and put everything in the prose -- the exact diagnostics
     * a machine consumer cannot act on, and the reason the {@code abandon} overload that omitted the two
     * arguments is gone. The prose is still free to explain; it is just no longer the only account.
     */
    @Test
    void aFacadeLevelFailureCarriesItsStructuredEndsToo() {
        Diagnostic unreachable = only(tsonWithPoint(), """
                !!schema:"https://example.test/not-there.tn"
                !point { x: 3  y: 4 }""");
        // Not "a resolvable schema": nothing was resolved, because nothing was obtained. The two share a
        // code and are told apart here, which is the half of the distinction that costs no schema version.
        assertEquals("a schema that can be obtained", unreachable.expected());
        assertEquals("https://example.test/not-there.tn", unreachable.actual(), "which schema could not be had");

        Diagnostic noRootRef = only(tsonWithPoint(), """
                !!schema:"https://example.test/point-1.tn"
                { x: 3  y: 4 }""");
        assertEquals("a root type-ref", noRootRef.expected());
        assertEquals("(none)", noRootRef.actual());
    }

    /**
     * {@code AtomTypeException}'s {@code expected} vocabulary survives the trip through a real schema, a real
     * compiled reader and {@code TsonReadContext.report} -- a membership, a length and a grammar, each landing
     * on the {@link Diagnostic} verbatim. {@code AtomTypeExceptionTest} pins the vocabulary itself, including
     * the shapes no schema can reach today (a {@code pattern} facet needs {@code regex_type} object-binding,
     * which is still a gap -- see {@code CLAUDE.md}'s "Not yet implemented").
     */
    @Test
    void everyExpectedShapeIsTheViolatedConstraint() {
        String schemaId = "https://example.test/facets-1.tn";
        String schema = """
                !!id:"https://example.test/facets-1.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  status => !enum [PENDING SHIPPED DELIVERED]
                  label  => !text ^ { max_length: 4 }
                  order  => { status: status  label: label  when: date }
                }
                """;
        Tson tson = Tson.builder().schemaSource(uri -> {
            if (uri.equals(schemaId)) {
                return schema;
            }
            throw new IllegalStateException("no schema for " + uri);
        }).build();

        List<Diagnostic> problems = tson.validate("!!schema:\"" + schemaId + "\"\n"
                + "!order { status: CANCELLED  label: toolong  when: nope }");

        assertEquals(3, problems.size(), problems.toString());
        assertEquals("one of (PENDING, SHIPPED, DELIVERED)", expectedAt(problems, "/status"));
        assertEquals("at most 4 characters", expectedAt(problems, "/label"));
        assertEquals("an RFC 3339 full-date", expectedAt(problems, "/when"));
    }

    private static String expectedAt(List<Diagnostic> problems, String path) {
        return problems.stream().filter(d -> d.path().equals(Optional.of(path))).findFirst()
                .orElseThrow(() -> new AssertionError("no diagnostic at " + path + " in " + problems))
                .expected();
    }

    /** The shape-mismatch path leaked twice -- the parser into {@code message}, and a raw event into {@code actual}. */
    @Test
    void aShapeMismatchAtAnAtomNamesTheTypeAndTheShape() {
        Diagnostic problem = only(tsonWithPoint(), """
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: { nested: 1 } }""");

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problem.code());
        assertEquals("a token for int32", problem.expected());
        assertEquals("a record", problem.actual());
        assertTrue(problem.message().contains("int32"), problem.message());
    }

    /**
     * The shape word, not the event. Every container reader reported {@code found
     * ArrayStart[position=Position[line=2, column=8, byteOffset=50]]} and put the same in {@code actual};
     * one {@code TypeRefCheck.describe} now serves all of them.
     */
    @Test
    void aContainerShapeMismatchNamesTheShapeNotTheEvent() {
        Diagnostic problem = only(tsonWithPoint(), """
                !!schema:"https://example.test/point-1.tn"
                !point [1 2]""");

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problem.code());
        assertEquals("a record", problem.expected());
        assertEquals("an array", problem.actual());
        assertTrue(problem.message().endsWith("found an array"), problem.message());
    }

    /** Every type-ref problem a schemaless document can carry, each reported once, at its own path. */
    @Test
    void aSchemalessDocumentsTypeRefsAreCheckedWhereverTheyAreWritten() {
        Tson tson = tsonWithPoint();

        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, only(tson, "{ a: !uuid nope }").code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, only(tson, "!uuid { a: 1 }").code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, only(tson, "!date [1 2]").code());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, only(tson, "{ a: !nosuchtype 1 }").code());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, only(tson, "!nosuchtype { a: 1 }").code());

        assertEquals(Optional.of("/a"), only(tson, "{ a: !uuid nope }").path());
    }

    /** An annotation's value is a data-value (§3.1), so validation reaches inside it. */
    @Test
    void anAnnotationsOwnValueIsValidatedToo() {
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                only(tsonWithPoint(), "{ a: @since:!date nope 1 }").code());
    }

    /** Several independent problems come back from one pass, not just the first. */
    @Test
    void everyProblemInOneDocumentIsCollected() {
        List<Diagnostic> problems = tsonWithPoint().validate("{ a: !uuid nope  b: !nosuchtype 1  c: !int8 999 }");
        assertEquals(3, problems.size(), problems.toString());
    }

    /** The sole diagnostic {@code source} produces -- asserts there is exactly one, then hands it over. */
    private static Diagnostic only(Tson tson, String source) {
        List<Diagnostic> problems = tson.validate(source);
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst();
    }

    /**
     * Content after the document's value is a problem, not a pass. Easy to lose: {@code TsonDataStream} is
     * lazy, so nothing notices unless the read pulls past the root value -- which a schema-driven validate
     * only does because it goes through {@link Tson#treeReader()}, whose framing owns that pull.
     */
    @Test
    void contentAfterTheDocumentsValueIsReported() {
        Tson tson = tsonWithPoint();
        String trailing = """
                !!schema:"https://example.test/point-1.tn"
                !point { x: 1  y: 2 } junk""";

        List<Diagnostic> problems = tson.validate(trailing);
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("unexpected content"), problems::toString);

        // and schemalessly, where a full parse catches it instead
        assertEquals(1, tson.validate("{ x: 1 } junk").size());
    }

    /**
     * Nothing about a bad *input document* reaches the caller as an exception -- validate's whole contract
     * is one shape to render. Malformed syntax and a schema document handed in where data was expected both
     * throw out of the reader underneath; validate converts them.
     */
    @Test
    void aMalformedOrWrongKindOfDocumentComesBackAsADiagnosticNotAnException() {
        Tson tson = tsonWithPoint();

        // A doubled comma: a trailing one is ordinary (§2.4 -- a comma may follow a value), where this one
        // follows a comma and so separates nothing.
        List<Diagnostic> syntax = tson.validate("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 1, , y: 2 }""");
        assertEquals(1, syntax.size(), syntax.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, syntax.getFirst().code());

        // A schema document, not data -- a well-formed document of a kind validate doesn't validate.
        List<Diagnostic> schemaDocument = tson.validate(POINT_SCHEMA);
        assertEquals(1, schemaDocument.size(), schemaDocument.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, schemaDocument.getFirst().code());
    }
}
