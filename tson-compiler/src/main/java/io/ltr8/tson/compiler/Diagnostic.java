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
 *   Reuses an existing IETF standard rather than a TSON-specific path syntax. Absent for a problem
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
 * <p><b>Both pointers are {@link Optional} because {@code ""} is a real pointer.</b> RFC 6901 spells
 * "the whole document" as the empty string, and this type emits it for real -- a document-level schema
 * problem such as an unloadable {@code !!import} genuinely points at the schema's root. Spelling
 * "nothing to say here" the same way makes the two indistinguishable to a consumer and to a renderer
 * alike, which is exactly the ambiguity the structured half exists to remove. An absent pointer means
 * this diagnostic has no such end at all; a present {@code ""} means the root.
 *
 * <p>The two ends are not alternatives: a value violating {@code int32} as core.tn declares it
 * populates both, which is why this is one record with a richer location model rather than separate
 * data- and schema-diagnostic types. That also matches every comparable system -- {@code
 * javax.tools.Diagnostic}, LSP's {@code Diagnostic}, rustc's {@code DiagInner} -- none of which
 * splits by *where* the problem is.
 *
 * <p><b>{@code message} and the structured fields do different jobs.</b> {@code code}/{@code path}/
 * {@code expected}/{@code actual} plus the positions carry the facts, and are what a caller (e.g. an LLM
 * retry loop) acts on without ever reading prose -- so every report site populates them, including the
 * facade-level ones. {@code message} is hand-composed at each {@link TsonReadContext#report} call site, for
 * a person, and is free to do what a rendering of the other fields could not: cite the spec, or name the
 * fix. It is deliberately <em>not</em> synthesized from {@code code} plus parameters -- {@code code} does
 * not determine the sentence ({@link Code#TYPE_MISMATCH} alone spans a wrong shape, a wrong token, a wrong
 * cardinality, a bare annotation, an unmatched variant and a host-binding failure), and the sentences differ
 * because the situations do. See {@code docs/readers-and-diagnostics.md}.
 */
public record Diagnostic(Optional<String> path, Optional<String> schemaPointer, String schemaId, Code code,
                          String message, String expected, String actual,
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
        String expected = "well-formed TSON";
        String actual = "a base-syntax error";
        switch (e) {
            case TsonParseException p -> {
                position = p.position();
                // A parse failure that named a construct carries the pair itself; one that stated a rule
                // (an adjacency violation, a trailing separator) has no substitution to describe.
                if (!p.expected().isEmpty()) {
                    expected = p.expected();
                    actual = p.actual();
                }
            }
            case io.ltr8.tson.compiler.lexer.LexException l -> position = l.position();
            case TsonUnsupportedDocumentException u -> position = u.position();
            default -> throw e;
        }
        return new Diagnostic(Optional.of(""), Optional.empty(), "", Code.VALIDATION_ERROR, e.getMessage(),
                expected, actual, Optional.ofNullable(position), Optional.empty());
    }

    /**
     * A *syntax* error in a schema document -- {@link #ofBaseSyntaxError}'s schema-side peer, and the shape
     * {@link TsonSchemaParser}'s recovering parse reports each failed declaration under.
     *
     * <p><b>It locates the problem at the schema end, not the data end.</b> A schema document is not data, so
     * {@code path}/{@code dataPosition} stay empty and the token's position lands in {@code schemaPosition}
     * beside a {@code /name} pointer -- the same two-ended model {@link #ofSchemaError} uses, so a syntax
     * error and a resolution error against the same declaration render identically. Running a schema
     * document's syntax error through {@code ofBaseSyntaxError} instead would point a consumer at the data
     * end of a diagnostic that has no data.
     *
     * <p>The code stays {@link Code#VALIDATION_ERROR}, the base-syntax code: where the failure is found is
     * carried by the location components, which is the whole reason there are four of them.
     *
     * @param declaration the declaration the error was found in, or {@code ""} for one outside any -- a
     *                    malformed header, an unterminated map -- which makes the pointer a <em>present</em>
     *                    {@code ""}, RFC 6901's own spelling of "the whole document"
     */
    public static Diagnostic ofSchemaSyntaxError(String schemaId, String declaration, TsonParseException e) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, Code.VALIDATION_ERROR, e.getMessage(),
                e.expected().isEmpty() ? "well-formed TSON" : e.expected(),
                e.actual().isEmpty() ? "a syntax error" : e.actual(),
                Optional.empty(), Optional.ofNullable(e.position()));
    }

    /**
     * {@link #ofSchemaSyntaxError(String, String, TsonParseException)} over the three ways a *schema* document
     * can fail before any declaration resolves, classified exactly as {@link #ofBaseSyntaxError} classifies
     * them and rethrowing anything else for the same reason. Lives here for the same reason too: {@code
     * LexException} is in the unexported {@code lexer} package, so a caller in another module cannot catch it.
     */
    public static Diagnostic ofSchemaSyntaxError(String schemaId, RuntimeException e) {
        SourcePosition position;
        switch (e) {
            case TsonParseException p -> {
                return ofSchemaSyntaxError(schemaId, "", p);
            }
            case io.ltr8.tson.compiler.lexer.LexException l -> position = l.position();
            case TsonUnsupportedDocumentException u -> position = u.position();
            default -> throw e;
        }
        return new Diagnostic(Optional.empty(), Optional.of(""), schemaId, Code.VALIDATION_ERROR, e.getMessage(),
                "well-formed TSON", "a syntax error", Optional.empty(), Optional.ofNullable(position));
    }

    /**
     * A problem in a *schema* rather than in data -- one declaration failed to resolve or link
     * ([TSON-DATA] §8.1's resolver-error category, which [TSON-SCHEMA] populates with "every error that
     * makes a schema fail to load or ingest"). The data end is empty because there is no data: this is
     * raised while the schema itself is being processed, before any document is read against it.
     *
     * @param schemaId    canonical identity of the schema the problem is in, empty if it isn't known (the
     *                    document may have failed before its own {@code !!id} could be read)
     * @param declaration the declared type name, which becomes the {@code /name} schema pointer. Empty for a
     *                    problem with the document as a whole rather than one of its declarations -- an
     *                    unloadable {@code !!import}, say -- which makes the pointer a <em>present</em>
     *                    {@code ""}, RFC 6901's own spelling of "the whole document", not an absence
     * @param position    where that declaration begins in the schema source, absent for a synthesized entry
     */
    public static Diagnostic ofSchemaError(String schemaId, String declaration, String message,
                                           Optional<SourcePosition> position) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, Code.SCHEMA_ERROR, message, "", "", Optional.empty(), position);
    }

    /**
     * A stable, machine-readable identifier from a closed vocabulary -- not a free string. The first eight
     * members are produced by an actual reader against real data; {@code UNRECOGNIZED_FIELD} carries
     * [TSON-SCHEMA] §7.2's record closure, so its {@code expected} is the type's own field list.
     *
     * <p>{@code DUPLICATE_MAP_KEY} and {@code DUPLICATE_FIELD} are the same mistake at the two container
     * shapes TSON keeps apart -- a key stated twice in one map ([TSON-DATA] §2.6), a field name stated
     * twice in one record (§2.5) -- and they stay two codes because the constructs are two, exactly as
     * {@code UNRECOGNIZED_FIELD} is record-specific. Both spec rules are written as a SHOULD-warn with a
     * defined recovery ("last value wins"); this implementation reports them as ordinary errors, which is
     * {@code SPEC-FEEDBACK.md} #41/#42's position: a warning presumes a human reader exercising judgment,
     * and the format's target consumer is a generate-validate-retry loop with exactly two behaviors, so a
     * severity axis would be machinery neither this type nor the format needs.
     *
     * <p>Every {@link io.ltr8.tson.compiler.atom.AtomTypeException} maps to the single {@code
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
        DUPLICATE_FIELD,
        SCHEMA_ERROR,
        UNKNOWN_TYPE,
        VALIDATION_ERROR
    }
}
