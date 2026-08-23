package io.ltr8.tson.compiler;

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
 * <p><b>Why the default is {@code SCHEMA_ERROR} rather than rethrowing.</b> {@link Diagnostic#ofBaseSyntaxError}
 * classifies the same way but ends {@code default -> throw e}, on the rule that a fault in this library
 * propagates as itself. That rule cannot be applied here, because {@link TsonSchemaSource#fetch} mandates
 * no exception type -- a source is free to signal an unfetchable schema with any {@code RuntimeException}
 * it likes, {@code IllegalStateException} included. At this boundary a broken invariant and a schema that
 * could not be found are genuinely indistinguishable by type, and rethrowing would turn every missing
 * schema into a crash for a source that spells it that way. The residual -- that a real fault in a resolve
 * or a compile still reads as a problem with the schema -- is a gap in the {@code fetch} contract, not in
 * this classification, and is tracked in {@code BACKLOG.md}.
 *
 * @param code     what to report the failure as
 * @param expected what the read needed, for the diagnostic's {@code expected} half; its {@code actual} is
 *                 the schema URI in every case, since that is the thing that could not be obtained
 */
record SchemaFailure(Diagnostic.Code code, String expected) {

    /**
     * Classifies a failure thrown while obtaining a compiled schema. Total by construction -- see this
     * type's own note on why there is no rethrowing branch.
     */
    static SchemaFailure of(RuntimeException e) {
        return switch (e) {
            // Subsumes TsonMissingBindingException, which is the same category: a type the reading
            // application never mapped, not a type the schema got wrong.
            case TsonBindMismatchException ignored ->
                    new SchemaFailure(Diagnostic.Code.BIND_MISMATCH, "a schema whose types the bound classes match");
            case UnsupportedOperationException ignored ->
                    new SchemaFailure(Diagnostic.Code.NOT_IMPLEMENTED, "a schema this library can compile");
            default -> new SchemaFailure(Diagnostic.Code.SCHEMA_ERROR, "a resolvable schema");
        };
    }
}
