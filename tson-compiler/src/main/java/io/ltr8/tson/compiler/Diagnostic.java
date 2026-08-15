package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * One problem found while reading a value against a compiled schema -- the unit both
 * {@link TsonReadException} (fail-fast mode) and a collecting {@link TsonReadContext} (multi-error
 * mode) report through, so both modes produce the identical shape of information regardless of which
 * one a caller chose.
 *
 * <p><b>A diagnostic locates a problem at two ends</b> -- the value in the data, and the rule in the
 * schema -- and either end may be absent. The four location components pair up as JSON Schema
 * 2020-12 §12's own output unit does, deliberately: {@code path} is {@code instanceLocation},
 * {@code schemaPointer} is {@code keywordLocation}, and {@code schemaId} plus {@code schemaPointer}
 * together are {@code absoluteKeywordLocation}.
 *
 * <ul>
 *   <li>{@code path} -- the *data* location, an RFC 6901 JSON Pointer (e.g. {@code /orders/3/total}).
 *   Reuses an existing IETF standard rather than a TSON-specific path syntax. Empty for a problem
 *   found in a schema, which has no data.</li>
 *   <li>{@code schemaPointer} -- the *schema* location, an RFC 6901 JSON Pointer into the schema's
 *   own {@code map<type_name, type_definition>} (e.g. {@code /my_type}). Deeper pointers
 *   ({@code /my_type/fields/x}) are the natural extension; positions are per declaration today.</li>
 *   <li>{@code schemaId} -- the canonical identity ([TSON-DATA] §2.2.1) of the schema
 *   {@code schemaPointer}/{@code schemaPosition} refer to. Without it a schema position is ambiguous
 *   across schemas: {@code 110:3:4858} is core.tn's line for {@code int32}, and nothing else says so.</li>
 *   <li>{@code dataPosition}/{@code schemaPosition} -- line/column/byte-offset in the *submitted
 *   document* and in the *schema source*. Either may be absent (a synthesized/materialized schema
 *   entry has no source position of its own, and not every data value's own position is tracked yet,
 *   see {@link TsonReadContext}'s own Javadoc).</li>
 * </ul>
 *
 * <p>The two ends are not alternatives: a value violating {@code int32} as core.tn declares it
 * populates both, which is why this is one record with a richer location model rather than separate
 * data- and schema-diagnostic types. That also matches every comparable system -- {@code
 * javax.tools.Diagnostic}, LSP's {@code Diagnostic}, rustc's {@code DiagInner} -- none of which
 * splits by *where* the problem is.
 *
 * <p>{@code message} is hand-composed at each {@link TsonReadContext#report} call site for now, not
 * synthesized purely from {@code code} plus {@code expected}/{@code actual} -- that would need a
 * richer per-code parameter shape than exists yet. {@code expected}/{@code actual} are the
 * machine-parseable pieces a caller (e.g. an LLM retry loop) can build its own message from without
 * parsing {@code message} itself.
 */
public record Diagnostic(String path, String schemaPointer, String schemaId, Code code, String message,
                          String expected, String actual,
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
        return new Diagnostic("", "", "", Code.VALIDATION_ERROR, e.getMessage(),
                "well-formed TSON", "a base-syntax error", Optional.ofNullable(position), Optional.empty());
    }

    /**
     * A problem in a *schema* rather than in data -- one declaration failed to resolve or link
     * ([TSON-DATA] §8.1's resolver-error category, which [TSON-SCHEMA] populates with "every error that
     * makes a schema fail to load or ingest"). The data end is empty because there is no data: this is
     * raised while the schema itself is being processed, before any document is read against it.
     *
     * @param schemaId    canonical identity of the schema the problem is in
     * @param declaration the declared type name, which becomes the {@code /name} schema pointer
     * @param position    where that declaration begins in the schema source, absent for a synthesized entry
     */
    public static Diagnostic ofSchemaError(String schemaId, String declaration, String message,
                                           Optional<SourcePosition> position) {
        return new Diagnostic("", "/" + declaration, schemaId, Code.SCHEMA_ERROR, message,
                "", "", Optional.empty(), position);
    }

    /**
     * A stable, machine-readable identifier from a closed vocabulary -- not a free string. The first
     * seven members are produced by an actual reader against real data; {@code UNRECOGNIZED_FIELD}/
     * {@code DUPLICATE_MAP_KEY} are reserved but not yet produced by any reader (see {@code
     * CLAUDE.md}'s "Positional read errors"/multi-error-collection notes for why); every {@link
     * io.ltr8.tson.compiler.atom.AtomTypeException} maps to the single {@code
     * ATOM_CONSTRAINT_VIOLATION} code for now, since {@code AtomValidationException} itself doesn't
     * yet carry a structured code to route on. That code is also, less accurately, what a
     * <em>schema-level</em> value violation reports under -- a document contradicting a FIXED field's value
     * (§5.2) is not an atom constraint at all, and wants a code of its own; see {@code BACKLOG.md}.
     * The last three ({@code SCHEMA_ERROR}/{@code
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
