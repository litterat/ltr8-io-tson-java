package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * One problem found while reading a value against a compiled schema -- the unit both
 * {@link TsonReadException} (fail-fast mode) and a collecting {@link TsonReadContext} (multi-error
 * mode) report through, so both modes produce the identical shape of information regardless of which
 * one a caller chose.
 *
 * <p>{@code path} is the *data* location, an RFC 6901 JSON Pointer (e.g. {@code /orders/3/total}) --
 * reuses an existing IETF standard rather than a TSON-specific path syntax, and matches the
 * convention JSON Schema's own standardized output format already uses for {@code instanceLocation}.
 * {@code dataPosition}/{@code schemaPosition} are the line/column/byte-offset this problem occurred
 * at in the *submitted document* and where the relevant type/field was *declared in the schema
 * source*, respectively -- either may be absent (a synthesized/materialized schema entry has no
 * source position of its own, and not every data value's own position is tracked yet, see {@link
 * TsonReadContext}'s own Javadoc).
 *
 * <p>{@code message} is hand-composed at each {@link TsonReadContext#report} call site for now, not
 * synthesized purely from {@code code} plus {@code expected}/{@code actual} -- that would need a
 * richer per-code parameter shape than exists yet. {@code expected}/{@code actual} are the
 * machine-parseable pieces a caller (e.g. an LLM retry loop) can build its own message from without
 * parsing {@code message} itself.
 */
public record Diagnostic(String path, Code code, String message, String expected, String actual,
                          Optional<SourcePosition> dataPosition, Optional<SourcePosition> schemaPosition) {

    /**
     * {@code e} as a base-syntax {@link Code#VALIDATION_ERROR} -- the three ways a document can fail before
     * any reader sees a value: it doesn't lex, it doesn't parse, or it's a well-formed document of a kind
     * this parser doesn't implement (§8.1 requires that last distinction be visible rather than folded into
     * "malformed").
     *
     * <p><b>Anything else is rethrown, deliberately.</b> {@code Tson.validate} promises never to throw for a
     * bad input <i>document</i>, which is not the same as never throwing: an exception that isn't one of
     * these three is a fault in this library, and turning it into a diagnostic would tell a caller their
     * document is invalid when it isn't -- burying the real failure and its stack trace behind a false
     * verdict. Classifying or rethrowing is one decision, so it is made here rather than handed back as an
     * {@code Optional} every call site has to unwrap the same way.
     *
     * <p>Lives here rather than on a validator because two of the three exception types are in the unexported
     * {@code lexer} package: a caller in another module cannot name them in a {@code catch} and so cannot
     * make this classification itself.
     */
    public static Diagnostic ofBaseSyntaxError(RuntimeException e) {
        SourcePosition position;
        switch (e) {
            case TsonParseException p -> position = p.position();
            case io.ltr8.tson.compiler.lexer.LexException l -> position = l.position();
            case TsonUnsupportedDocumentException u -> position = u.position();
            default -> throw e;
        }
        return new Diagnostic("", Code.VALIDATION_ERROR, e.getMessage(),
                "well-formed TSON", "a base-syntax error", Optional.ofNullable(position), Optional.empty());
    }

    /**
     * A stable, machine-readable identifier from a closed vocabulary -- not a free string. The first
     * seven members are produced by an actual reader against real data; {@code UNRECOGNIZED_FIELD}/
     * {@code DUPLICATE_MAP_KEY} are reserved but not yet produced by any reader (see {@code
     * CLAUDE.md}'s "Positional read errors"/multi-error-collection notes for why); every {@link
     * io.ltr8.tson.compiler.atom.AtomTypeException} maps to the single {@code
     * ATOM_CONSTRAINT_VIOLATION} code for now, since {@code AtomValidationException} itself doesn't
     * yet carry a structured code to route on. The last three ({@code SCHEMA_ERROR}/{@code
     * UNKNOWN_TYPE}/{@code VALIDATION_ERROR}) are infrastructure-level fallbacks a caller (e.g.
     * {@code tson-cli}) uses for a failure that happens outside any single {@link TsonReadContext}
     * read at all -- the schema itself failed to compile, a requested type name doesn't exist in it,
     * or some other unexpected exception was thrown before a collecting context ever got involved.
     */
    public enum Code {
        FIELD_REQUIRED,
        TYPE_MISMATCH,
        WRONG_ARITY,
        UNKNOWN_TYPE_REF,
        ATOM_CONSTRAINT_VIOLATION,
        UNRECOGNIZED_FIELD,
        DUPLICATE_MAP_KEY,
        SCHEMA_ERROR,
        UNKNOWN_TYPE,
        VALIDATION_ERROR
    }
}
