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
