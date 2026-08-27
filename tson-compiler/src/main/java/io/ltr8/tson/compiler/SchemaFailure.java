package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonSchemaValidationException;

/**
 * What a read reports when it cannot obtain a compiled schema for the document it is reading -- the {@link
 * Diagnostic.Code} and the {@code expected} that goes with it, chosen by what actually failed.
 *
 * <p><b>Why this is not one code.</b> Both read facades reach their schema through a single call that
 * resolves, links and compiles, so every way any of those can fail arrives at one {@code catch}. Coding
 * them all {@code SCHEMA_ERROR} tells a consumer the author's schema is wrong, which for two of them is a
 * false verdict: a {@link TsonBindMismatchException} says the schema and the reading application's own
 * classes disagree (both are fine; they have been pointed at each other by mistake), and an {@code
 * UnsupportedOperationException} says a construct is beyond this library. Neither is a statement about the
 * document or the schema, and a consumer routing on the code -- picking an HTTP status, picking an exit
 * code -- needs them apart. This is {@code NOT_IMPLEMENTED}'s own argument one step further out: let the
 * code carry the distinction the channel cannot.
 *
 * <p><b>Every branch is a positive verdict, and the default rethrows.</b> {@link
 * Diagnostic#ofBaseSyntaxError} classifies the same way and ends {@code default -> throw e}, on the rule
 * that a fault in this library propagates as itself; this holds to it. What makes that possible is {@link
 * TsonSchemaSource#fetch} naming the exception a source must throw for "cannot supply this" ({@link
 * TsonSchemaFetchException}) -- without it, an unfetchable schema and a broken invariant are
 * indistinguishable by type here, and either every fault reads as a bad schema or every source that spells
 * a miss with an {@code IllegalStateException} crashes the read.
 *
 * <p><b>Not obtaining a schema and not resolving one are two codes</b>, {@code SCHEMA_UNAVAILABLE} and
 * {@code SCHEMA_ERROR}. The first is not a verdict on anything: no source would supply the schema, so it was
 * never read, and whether it would have resolved is unknown. The second is a verdict -- the schema arrived
 * and is wrong. A pinned reference whose bytes do not match its digest is the second: something was
 * obtained, and it is not what the reference named.
 *
 * @param code     what to report the failure as
 * @param expected what the read needed, for the diagnostic's {@code expected} half; its {@code actual} is
 *                 the schema URI in every case, since that is the thing that could not be obtained
 */
record SchemaFailure(Diagnostic.Code code, String expected) {

    /**
     * Classifies a failure thrown while obtaining a compiled schema, rethrowing anything none of the four
     * categories claims -- see this type's own note.
     *
     * @throws RuntimeException {@code e} itself, when it is a fault rather than a verdict
     */
    static SchemaFailure of(RuntimeException e) {
        return switch (e) {
            // Subsumes TsonMissingBindingException, which is the same category: a type the reading
            // application never mapped, not a type the schema got wrong.
            case TsonBindMismatchException ignored ->
                    new SchemaFailure(Diagnostic.Code.BIND_MISMATCH, "a schema whose types the bound classes match");
            case UnsupportedOperationException ignored ->
                    new SchemaFailure(Diagnostic.Code.NOT_IMPLEMENTED, "a schema this library can compile");
            // The contract exception of TsonSchemaSource.fetch, so this branch is every source's miss and
            // no source's bug: the reference named something this deployment could not obtain.
            case TsonSchemaFetchException ignored ->
                    new SchemaFailure(Diagnostic.Code.SCHEMA_UNAVAILABLE, "a schema that can be obtained");
            case TsonSchemaValidationException ignored ->
                    new SchemaFailure(Diagnostic.Code.SCHEMA_ERROR, "a resolvable schema");
            // [TSON-DATA] §2.2.1's integrity failure: the bytes a source returned are not the bytes the
            // reference pinned. A verdict on the reference, so it is coded like one -- and never a fault,
            // which is what it would be classified as if it fell to the default below.
            case TsonContentHashMismatchException ignored ->
                    new SchemaFailure(Diagnostic.Code.SCHEMA_ERROR, "a schema matching its ?sha256= pin");
            default -> throw e;
        };
    }
}
