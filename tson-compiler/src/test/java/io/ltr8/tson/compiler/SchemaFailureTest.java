package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonSchemaValidationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The policy table behind both read facades' one {@code catch}: a read reaches its schema through a single
 * call that resolves, links and compiles, so every way any of those can fail arrives at the same place, and
 * the code is all a consumer has left to route on.
 *
 * <p>Each case here is a different <em>party</em> at fault -- the schema's author, the reading application's
 * wiring, whoever was to serve the schema, this library -- which is exactly what a consumer picking an HTTP
 * status or an exit code is asking about, and exactly what one shared code erases. The last of the four is
 * not a verdict at all and does not become one: it is rethrown.
 */
class SchemaFailureTest {

    /** The author's: the schema could not be fetched, or would not resolve or link. */
    @Test
    void aSchemaThatWillNotLoadIsTheSchemasOwnProblem() {
        assertEquals(Diagnostic.Code.SCHEMA_ERROR,
                SchemaFailure.of(new TsonSchemaValidationException("'x' is not registered")).code());
    }

    /**
     * The reading application's: the schema is fine and the class is fine, and they have been pointed at
     * each other by mistake. Nothing about the document is wrong, so nothing here is a verdict on it.
     */
    @Test
    void aBindMismatchIsTheReadingApplicationsProblem() {
        assertEquals(Diagnostic.Code.BIND_MISMATCH,
                SchemaFailure.of(new TsonBindMismatchException("no component for field 'currency'")).code());
    }

    /**
     * Including its missing-binding half. A type the caller never mapped is the same category -- a mapping
     * that was not made, rather than a schema that is wrong -- and it inherits the code by being a subclass,
     * which is the whole reason it is one.
     */
    @Test
    void aMissingBindingIsTheSameCategoryAsAMismatch() {
        assertEquals(Diagnostic.Code.BIND_MISMATCH,
                SchemaFailure.of(new TsonMissingBindingException("no bound Java class for 'order'")).code());
    }

    /** This library's, and already spoken for: a construct beyond it could not be checked, not rejected. */
    @Test
    void aGapKeepsTheCodeThatSaysItCouldNotBeChecked() {
        assertEquals(Diagnostic.Code.NOT_IMPLEMENTED,
                SchemaFailure.of(new UnsupportedOperationException("no compiled reader for 'extern'")).code());
    }

    /**
     * Nobody's: no source would supply the schema the document names, so it was never read. <b>Not {@code
     * SCHEMA_ERROR}</b> -- that is a verdict, and this run has no grounds for one about a schema it never
     * saw. Whether that schema would even have resolved is unknown.
     */
    @Test
    void anUnfetchableSchemaIsNotAVerdictOnTheSchema() {
        TsonSchemaFetchException unfetchable = new TsonSchemaFetchException("https://example.test/x.tn",
                TsonSchemaFetchException.Reason.TIMEOUT, "no answer in 5s", null);

        assertEquals(Diagnostic.Code.SCHEMA_UNAVAILABLE, SchemaFailure.of(unfetchable).code());
        assertEquals("a schema that can be obtained", SchemaFailure.of(unfetchable).expected());
    }

    /**
     * A pinned reference whose bytes do not match its digest stays a verdict, and stays {@code
     * SCHEMA_ERROR}: something <em>was</em> obtained, and it is not what the reference named
     * ([TSON-DATA] §2.2.1). The line between the two schema codes is whether a document arrived, not
     * whether it was usable.
     */
    @Test
    void aPinMismatchIsAVerdictOnTheReference() {
        SchemaFailure failure = SchemaFailure.of(new TsonContentHashMismatchException("digest differs"));

        assertEquals(Diagnostic.Code.SCHEMA_ERROR, failure.code());
        assertEquals("a schema matching its ?sha256= pin", failure.expected());
    }

    /**
     * <b>Anything else is a fault, and propagates as itself</b> -- the rule {@link
     * Diagnostic#ofBaseSyntaxError} states and this now shares. What makes it applicable is {@link
     * TsonSchemaSource#fetch} naming {@link TsonSchemaFetchException} as the way a source says "cannot
     * supply this": with no mandated type, an {@code IllegalStateException} here could equally be a source's
     * miss or a broken invariant, and every classification of it is wrong half the time.
     */
    @Test
    void aFaultPropagatesAsItself() {
        IllegalStateException fault = new IllegalStateException("compiled readers rebound twice");

        assertSame(fault, assertThrows(IllegalStateException.class, () -> SchemaFailure.of(fault)));
    }

    /** Each code brings the {@code expected} that goes with it, so the structured half never contradicts it. */
    @Test
    void theExpectedHalfFollowsTheCode() {
        assertEquals("a schema whose types the bound classes match",
                SchemaFailure.of(new TsonBindMismatchException("x")).expected());
        assertEquals("a schema this library can compile",
                SchemaFailure.of(new UnsupportedOperationException("x")).expected());
        assertEquals("a resolvable schema", SchemaFailure.of(new TsonSchemaValidationException("x")).expected());
    }
}
