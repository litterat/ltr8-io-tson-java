package io.ltr8.tson;

import io.ltr8.tson.compiler.ContentHash;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { point => { x: int32  y: int32 } }
            """;

    private static Tson tsonWithPoint() {
        TsonSchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;   // ignore any ?sha256= pin
            if (base.equals(POINT_ID)) {
                return POINT_SCHEMA;
            }
            throw new IllegalStateException("no schema for " + uri);
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

    @Test
    void aSchemaTheSourceCannotProvideIsASchemaError() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/not-there.tn"
                !point { x: 3  y: 4 }""");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
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
    void plainAndPinnedReferencesToOneSchemaBothResolve() {
        // Two references differing only by a ?sha256= pin are one identity: the schema resolves and
        // registers once, so both validate rather than the second double-registering. (Both use the
        // correct pin so verification passes -- mismatch is its own test below.)
        String hash = ContentHash.sha256(POINT_SCHEMA.getBytes(StandardCharsets.UTF_8));
        Tson tson = tsonWithPoint();

        assertEquals(List.of(), tson.validate("!!schema:\"" + POINT_ID + "\"\n!point { x: 1  y: 2 }"));
        assertEquals(List.of(), tson.validate(
                "!!schema:\"" + POINT_ID + "?sha256=" + hash + "\"\n!point { x: 3  y: 4 }"));
    }

    @Test
    void aCorrectlyPinnedReferenceVerifies() {
        String hash = ContentHash.sha256(POINT_SCHEMA.getBytes(StandardCharsets.UTF_8));
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
        String correctHash = ContentHash.sha256(POINT_SCHEMA.getBytes(StandardCharsets.UTF_8));
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

    /** Every type-ref problem a schemaless document can carry, each reported once, at its own path. */
    @Test
    void aSchemalessDocumentsTypeRefsAreCheckedWhereverTheyAreWritten() {
        Tson tson = tsonWithPoint();

        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, only(tson, "{ a: !uuid nope }").code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, only(tson, "!uuid { a: 1 }").code());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, only(tson, "!date [1 2]").code());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, only(tson, "{ a: !nosuchtype 1 }").code());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, only(tson, "!nosuchtype { a: 1 }").code());

        assertEquals("/a", only(tson, "{ a: !uuid nope }").path());
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

        List<Diagnostic> syntax = tson.validate("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 1, }""");
        assertEquals(1, syntax.size(), syntax.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, syntax.getFirst().code());

        // A schema document, not data -- a well-formed document of a kind validate doesn't validate.
        List<Diagnostic> schemaDocument = tson.validate(POINT_SCHEMA);
        assertEquals(1, schemaDocument.size(), schemaDocument.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, schemaDocument.getFirst().code());
    }
}
