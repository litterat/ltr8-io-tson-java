package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonSchemaValidationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The policy table behind both read facades' one {@code catch}: a read reaches its schema through a single
 * call that resolves, links and compiles, so every way any of those can fail arrives at the same place, and
 * the code is all a consumer has left to route on.
 *
 * <p>Each case here is a different <em>party</em> at fault -- the schema's author, the reading application's
 * wiring, this library -- which is exactly what a consumer picking an HTTP status or an exit code is asking
 * about, and exactly what one shared code erases.
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
     * <b>The default is a verdict on the schema, not a rethrow</b>, which is where this classification
     * deliberately parts from {@link Diagnostic#ofBaseSyntaxError}'s otherwise identical shape. {@link
     * TsonSchemaSource#fetch} mandates no exception type, so a source may signal an unfetchable schema with
     * anything at all -- and rethrowing the types this library reserves for its own faults would turn a
     * missing schema into a crash for any source that spells it that way. See {@code SchemaFailure}'s own
     * note; the residual is tracked in {@code BACKLOG.md}.
     */
    @Test
    void anythingElseIsReportedRatherThanThrown() {
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, SchemaFailure.of(new IllegalStateException("no such file")).code());
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
