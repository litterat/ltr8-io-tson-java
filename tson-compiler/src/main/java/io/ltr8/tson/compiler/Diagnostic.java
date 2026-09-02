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
 *   <li>{@code schemaPointer} -- the *schema* location, an RFC 6901 JSON Pointer. On the read path it is
 *   the path taken through the schema being validated against ({@code /person/age}), which is a literal
 *   pointer into that schema document as written; on the schema path it is the failing declaration
 *   ({@code /my_type}), because a schema problem is about the declaration itself and no validation path
 *   led to it. See {@link SchemaLocation} for how a read accumulates one.</li>
 *   <li>{@code schemaId} -- the canonical identity ([TSON-DATA] §2.2.1) of the schema
 *   {@code schemaPointer}/{@code schemaPosition} refer to. Without it a schema position is ambiguous
 *   across schemas: {@code 110:3:4858} is core.tn's line for {@code int32}, and nothing else says so.</li>
 *   <li>{@code dataPosition}/{@code schemaPosition} -- line/column/byte-offset in the *submitted
 *   document* and in the *schema source*. Either may be absent (a synthesized/materialized schema
 *   entry has no source position of its own, and not every data value's own position is tracked yet,
 *   see {@link TsonReadContext}'s own Javadoc). {@code schemaPosition} is per <em>declaration</em>, so it
 *   is one level coarser than a read-path pointer: {@code /person/age} carries {@code person}'s own line.</li>
 * </ul>
 *
 * <p><b>Both pointers are {@link Optional} because {@code ""} is a real pointer.</b> RFC 6901 spells
 * "the whole document" as the empty string, and this type emits it for real -- a document-level schema
 * problem such as an unloadable {@code !!import} genuinely points at the schema's root. Spelling
 * "nothing to say here" the same way makes the two indistinguishable to a consumer and to a renderer
 * alike, which is exactly the ambiguity the structured half exists to remove. An absent pointer means
 * this diagnostic has no such end at all; a present {@code ""} means the root.
 *
 * <p><b>Every component is a location</b>, and the one fact that is not -- why a schema could not be
 * obtained -- is carried by the {@link Code} rather than beside it. A fetch failure has five causes and
 * consumers partition them differently (a command line by whether a rerun could help, an HTTP surface by
 * whose doing it was), so each is its own code and no partition is privileged. A field would have been a
 * second carrier for a routing question, and the code is what a consumer routes on.
 *
 * <p><b>A [TSON-DATA] §8.2 name-hygiene refusal carries no component of its own</b> either, for a related
 * reason. §8.2 requires a refusal to name the Unicode data
 * version it was computed against, which is a fact about <em>this processor</em>: constant for the life of
 * a process, so a copy on each problem is N copies of a string that cannot differ, and needed by a sender
 * before it writes a document rather than after it is refused. It is stated once, beside the diagnostics
 * rather than inside them ({@link TsonUnicodeProcessorPolicy}), which also states the thing a version cannot --
 * what this deployment <em>would</em> admit. What is left here is the remedy: which name failed, and
 * <b>which rule fired, which is the code</b> ({@link Code#CONFUSABLE_NAMES}, {@link
 * Code#RESTRICTED_CHARACTER}, {@link Code#RESTRICTED_SCRIPT}, one each).
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

    // ── Absence, for a renderer ──────────────────────────────────────────
    //
    // This record spells "nothing to say here" two ways, and both are deliberate: `""` for the three
    // components below, `Optional` for the two pointers -- where `""` is not absence but the *root*, a
    // location a document-level schema problem genuinely carries. That split is right at the source and
    // useless at the sink: anything rendering a diagnostic (a CLI's own wire DTO, an HTTP error body) wants
    // one answer to "is there anything here", and had to know per component which convention applies. These
    // three say it once, so a renderer asks rather than remembers.

    /**
     * The schema this problem is in, if the diagnostic knows -- absent for a schemaless read, and for a
     * document that failed before its own {@code !!id} could be read.
     */
    public Optional<String> schemaIdIfKnown() {
        return stated(schemaId);
    }

    /**
     * The constraint that failed, if the throw site named one. Absent where it stated a <em>rule</em> rather
     * than a substitution -- an adjacency violation, a trailing separator -- which has no "expected this,
     * found that" to give.
     */
    public Optional<String> expectedIfStated() {
        return stated(expected);
    }

    /** What was found instead, paired with {@link #expectedIfStated} and absent under the same rule. */
    public Optional<String> actualIfStated() {
        return stated(actual);
    }

    /** This record's {@code ""}-means-nothing convention, as an absence. */
    private static Optional<String> stated(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

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
     * <p><b>The readers call this for you.</b> Every whole-document entry point on {@link TsonTreeReader}/
     * {@link TsonObjectReader} routes a base-syntax failure through the read's own receiver, so a collecting
     * read gets it as a diagnostic and a fail-fast one throws from the receiver. What is left for a caller
     * is the case of driving a {@code TsonDataStream} or {@code TsonDataParser} directly, where there is no
     * receiver to route through.
     *
     * <p>Public rather than private to the readers because a caller who does that cannot make this
     * classification themselves: {@link io.ltr8.tson.compiler.lexer.LexException} is in the unexported
     * {@code lexer} package, so it cannot be named in a {@code catch} from another module -- and catching
     * {@link RuntimeException} instead is only safe if something else separates a base-syntax failure from a
     * fault in this library, which is exactly what this does.
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
     * A token whose scripts the read's {@code TsonUnicodePolicy} does not permit ([TSON-DATA] §8.2's
     * "Values", UTS #39 §5.2).
     *
     * <p><b>Always {@link Code#RESTRICTED_SCRIPT}.</b> A token is not a name, so it has no identifier profile
     * and no scope to be distinct within; §8.2's restricted-script rule is the only one of the three a value
     * surface can carry.
     *
     * <p><b>{@code why} names the text it judged, so this does not name it again.</b> {@code
     * TsonUnicodePolicy.violation} opens with the unit it refused ({@code 'аdmin' mixes the scripts ...}),
     * which is what makes {@code "the token " + why} read as one sentence -- the same composition {@code
     * DefaultTsonReadContext.refuse} uses for a name.
     *
     * <p><b>No {@code path}, and that is not an omission.</b> The check runs where tokens leave the stream,
     * before any reader has descended into them, which is exactly what lets it see a value and a field name
     * alike. There is no path yet to state, so the diagnostic carries the one location it really has.
     */
    public static Diagnostic ofRestrictedToken(String text, String why, SourcePosition position) {
        return new Diagnostic(Optional.empty(), Optional.empty(), "", Code.RESTRICTED_SCRIPT,
                "the token " + why, "a token the Unicode policy admits", text,
                Optional.ofNullable(position), Optional.empty());
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
     * The schema could not be obtained at all -- {@link #ofSchemaError}'s shape with {@link
     * Code#of(TsonSchemaFetchException.Reason) fetch code} in place of {@code SCHEMA_ERROR}, and the whole
     * of the difference between
     * "this schema is wrong" and "nobody would give me this schema".
     *
     * <p><b>It takes the exception rather than its message</b>, because it is built from a {@link
     * TsonSchemaFetchException} and from nothing else -- which is what makes the distinction cheap: {@link
     * TsonSchemaSource#fetch} names that type for "cannot supply this", so the two cases never have to be
     * told apart by reading a message. An {@code !!import} or {@code !!meta} naming an identity no source
     * will serve reaches this; one that resolves and then fails to link is a {@code SCHEMA_ERROR} like any
     * other.
     *
     * <p>Taking the whole exception is also what picks the {@link Code}, and what fills the {@code
     * expected}/{@code actual} pair: the reference that could not be obtained is {@code actual}, since it is
     * the thing a consumer must look at. The alternative -- passing a flattened message -- is how this
     * factory came to state {@link TsonSchemaFetchException.Reason}'s distinction nowhere.
     */
    public static Diagnostic ofSchemaUnavailable(String schemaId, String declaration,
                                                 TsonSchemaFetchException e, Optional<SourcePosition> position) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, Code.of(e.reason()), e.getMessage(), SchemaFailure.UNAVAILABLE_EXPECTED, e.uri(),
                Optional.empty(), position);
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
     * A [TSON-DATA] §8.2 name-hygiene <b>refusal</b> at one declaration -- {@link #ofSchemaError}'s shape
     * with a policy code in place of {@code SCHEMA_ERROR}. The schema is not wrong: it is refused by this
     * processor, under a policy reading data the Unicode Consortium declines to freeze, and §8.2 says such
     * a refusal MUST NOT be reported in any of §8.1's four categories.
     *
     * <p>The peer of what a read reports for the same rule, so one schema and one document that break the
     * same rule come back under the same code. {@code code} is {@link Code#CONFUSABLE_NAMES} for names that
     * read alike, {@link Code#RESTRICTED_CHARACTER} for a character outside the identifier profile and {@link
     * Code#RESTRICTED_SCRIPT} for a script combination the restriction level does not admit -- the caller picks,
     * because the rule is what it knows and the code is what a consumer routes on. One code per rule, so the
     * code alone tells an author which fix applies.
     *
     * <p><b>It is still a verdict on the schema</b>, and a consumer treats it as one: the document must
     * change, or the deployment must relax the policy in code. What it is not is a claim that the format
     * says the schema is malformed, which is why it does not ride {@code SCHEMA_ERROR}.
     */
    public static Diagnostic ofSchemaRefusal(String schemaId, String declaration, Code code, String message,
                                             Optional<SourcePosition> position) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, code, message, "", "", Optional.empty(), position);
    }

    /**
     * A schema and a class bound to it that cannot work together -- {@link #ofSchemaError}'s shape with
     * {@link Code#BIND_MISMATCH} in place of {@code SCHEMA_ERROR}, and the difference between "your schema is
     * wrong" and "this application is wired wrong".
     *
     * <p>Built from a {@link TsonBindMismatchException} and from nothing else. It exists so a collecting
     * caller hears about a wiring mistake in the same list as everything else rather than having it thrown
     * past them as though it were a fault in this library: the schema may be perfectly good, and the message
     * names one of the caller's own classes.
     */
    public static Diagnostic ofSchemaBindMismatch(String schemaId, String declaration,
                                                  TsonBindMismatchException e,
                                                  Optional<SourcePosition> position) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, Code.BIND_MISMATCH, e.getMessage(), "", "", Optional.empty(), position);
    }

    /**
     * A construct this library has not implemented, found at one declaration -- {@link #ofSchemaError}'s
     * shape with {@link Code#NOT_IMPLEMENTED} in place of {@code SCHEMA_ERROR}, and the whole of the
     * difference between "your schema is wrong" and "this schema could not be checked".
     *
     * <p><b>Why a gap travels as a diagnostic at all</b>, when the classification policy keeps the two
     * exceptions strictly apart. A gap thrown out of a phase that reports per declaration takes the other
     * declarations' verdicts with it: one unimplemented construct and the author learns nothing about the
     * rest of a document that may have several ordinary errors in it. Carrying it in the same list keeps
     * the pass single, and the code -- not the channel -- is what keeps the verdict distinguishable, which
     * is all the policy ever needed to preserve. The exception classification itself is unchanged: this is
     * built from an {@code UnsupportedOperationException} and from nothing else.
     */
    public static Diagnostic ofSchemaGap(String schemaId, String declaration, String message,
                                         Optional<SourcePosition> position) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, Code.NOT_IMPLEMENTED, message, "", "", Optional.empty(), position);
    }

    /**
     * A stable, machine-readable identifier from a closed vocabulary -- not a free string. The first nine
     * members are produced by an actual reader against real data; {@code UNRECOGNIZED_FIELD} carries
     * [TSON-SCHEMA] §7.2's record closure, so its {@code expected} is the type's own field list.
     *
     * <p>{@code FIELD_REQUIRED} and {@code FIELD_FIXED} are the two [TSON-SCHEMA] §5.2 field-state rules a
     * document can break, and they sit together deliberately: neither is anything to do with the field's
     * <em>type</em>. A {@code FIELD_FIXED} value satisfied its atom's grammar and every facet -- it simply
     * isn't the one value the schema permits, whether that is a stated value contradicting {@code = value},
     * a {@code REQUIRED_FIXED} field written {@code _}, or a value written where {@code = _} fixes the
     * field to absent.
     *
     * <p>{@code DUPLICATE_MAP_KEY} and {@code DUPLICATE_FIELD} are the same mistake at the two container
     * shapes TSON keeps apart -- a key stated twice in one map ([TSON-DATA] §2.6), a field name stated
     * twice in one record (§2.5) -- and they stay two codes because the constructs are two, exactly as
     * {@code UNRECOGNIZED_FIELD} is record-specific. Both are MUST NOT, reported at the repeated occurrence,
     * with last-value-wins surviving only as the recovery underneath. There is no severity component here
     * and none is coming: §8.1 states that a conforming processor has one severity and that nothing
     * normative is satisfied, relaxed, or deferred by an advisory notice.
     *
     * <p>Every {@link io.ltr8.tson.compiler.atom.AtomTypeException} maps to the single {@code
     * ATOM_CONSTRAINT_VIOLATION} code, since {@code AtomValidationException} itself doesn't yet carry a
     * structured code to route on -- so that member means "the atom rejected this token", nothing finer.
     * Routing its varieties apart is tracked in {@code BACKLOG.md}.
     * {@code SCHEMA_ERROR}/{@code UNKNOWN_TYPE}/{@code VALIDATION_ERROR} are infrastructure-level
     * fallbacks a caller (e.g. {@code tson-cli}) uses for a failure that happens outside any single
     * {@link TsonReadContext} read at all -- the schema itself failed to load, a requested type name
     * doesn't exist in it, or some other unexpected exception was thrown before a collecting context ever
     * got involved. {@code SCHEMA_ERROR} means the schema is at fault and nothing else: a failure obtaining
     * a schema that is <em>not</em> the schema's fault carries its own code (below), rather than being
     * flattened into this one because it arrived through the same catch.
     *
     * <p><b>{@code CONFUSABLE_NAMES}, {@code RESTRICTED_CHARACTER} and {@code RESTRICTED_SCRIPT} are one code
     * per [TSON-DATA] §8.2 name-hygiene rule</b>, and the split is what carries the rule: a refusal is §8.1's
     * fifth outcome rather than a verdict, and the three want three different fixes -- rename one of a
     * colliding pair, change a character outside the identifier profile, or relax the policy's level or name
     * a script set. A consumer routes on the code, so the rule belongs in it; a second enum beside the code
     * would only be a fact the code already fixes, free to disagree with it.
     *
     * <p><b>{@code RESTRICTED_SCRIPT} is a script the policy does not admit, which is wider than a mix.</b>
     * A script <em>combination</em> is the usual finding, and at {@link TsonUnicodePolicy.Level#ASCII_ONLY}
     * a single-script name is refused with nothing mixed at all -- so the code names what the policy would
     * not admit rather than what the text did, and pairs with {@code RESTRICTED_CHARACTER} as the two halves
     * of one identifier policy. It is also the one of the three a <em>value</em> can carry ({@link
     * #ofRestrictedToken}), a token having no identifier profile and no scope to be distinct within.
     *
     * <p><b>{@code NOT_IMPLEMENTED}, {@code BIND_MISMATCH} and the five {@code SCHEMA_*} fetch codes are
     * the members that are not a verdict on the document</b> ({@link Code#verdict}). Each says the thing it
     * names could not be checked, which is not the same as invalid, and a consumer that treats any of them
     * as invalid is wrong in the one direction that matters. They differ in <em>who</em> could not check it,
     * which is exactly what a consumer picking an HTTP status or an exit code is asking:
     * <ul>
     *   <li>{@code NOT_IMPLEMENTED} -- this library. A construct is beyond it; the member exists so a gap
     *       can ride in the same single-pass list as the ordinary problems rather than throwing and taking
     *       their verdicts with it.</li>
     *   <li>{@code BIND_MISMATCH} -- the reading application. A schema and the Java classes bound to it
     *       disagree ({@link TsonBindMismatchException}, {@link TsonMissingBindingException}): a wiring
     *       mistake, where the document may be perfectly valid and the message names one of that
     *       application's own classes.</li>
     *   <li>the five {@code SCHEMA_*} codes -- everyone else. No configured {@link TsonSchemaSource} would
     *       supply the schema the document names ({@link TsonSchemaFetchException}). Nothing is wrong with
     *       the document, and nothing may be wrong with the schema either -- it was never obtained, so it
     *       was never read. Kept apart from {@code SCHEMA_ERROR} because that one is a verdict: the schema
     *       <em>was</em> obtained and it does not resolve. <b>"Everyone else" is several people</b>, and
     *       that is why there are five: a reference this deployment refuses is the sender's mistake where a
     *       host that timed out is nobody's, and only one of those is worth retrying.</li>
     * </ul>
     */
    public enum Code {
        FIELD_REQUIRED,
        FIELD_FIXED,
        TYPE_MISMATCH,
        WRONG_ARITY,
        UNKNOWN_TYPE_REF,
        ATOM_CONSTRAINT_VIOLATION,
        UNRECOGNIZED_FIELD,
        DUPLICATE_MAP_KEY,
        DUPLICATE_FIELD,
        CONFUSABLE_NAMES,
        RESTRICTED_CHARACTER,
        RESTRICTED_SCRIPT,
        SCHEMA_ERROR,
        UNKNOWN_TYPE,
        VALIDATION_ERROR,
        NOT_IMPLEMENTED,
        BIND_MISMATCH,

        // ── A schema was not obtained: one code per reason ───────────────────────────────────────
        //
        // Why a fetch failed is a *routing* question, and a code is what a consumer routes on -- the same
        // reason §8.2's three refusal codes are three codes with no `refusalReason` beside them. Carrying it
        // as a field instead would be a second carrier for one fact, free to disagree with the first.
        //
        // One per reason rather than a permanent/transient pair, because consumers partition them
        // differently: a command line by whether a rerun could help, an HTTP surface by whose doing it was.
        // A code encoding one partition strands the other. `TsonSchemaFetchException.Reason` is the throwing
        // channel's own vocabulary and the single input to `Code.of`, so the two channels cannot disagree.

        /** Policy refused it: not an allowed host, not a legal identity, or no pin where one is required. */
        SCHEMA_NOT_PERMITTED,

        /** The location was reached and does not have it. */
        SCHEMA_NOT_FOUND,

        /** The location could not be reached, or answered with something other than a document. */
        SCHEMA_UNREACHABLE,

        /** The location did not answer in time. */
        SCHEMA_TIMEOUT,

        /** The location answered with more bytes than a schema document is allowed to be. */
        SCHEMA_TOO_LARGE;

        /** The code a fetch failure reports, one per {@link TsonSchemaFetchException.Reason}. */
        public static Code of(TsonSchemaFetchException.Reason reason) {
            return switch (reason) {
                case NOT_PERMITTED -> SCHEMA_NOT_PERMITTED;
                case NOT_FOUND -> SCHEMA_NOT_FOUND;
                case TRANSPORT -> SCHEMA_UNREACHABLE;
                case TIMEOUT -> SCHEMA_TIMEOUT;
                case TOO_LARGE -> SCHEMA_TOO_LARGE;
            };
        }

        /**
         * Whether this code is a verdict on the document -- <b>the document was checked, and this is what
         * checking found</b>.
         *
         * <p>The three that are not say so for three different reasons: {@link #NOT_IMPLEMENTED} that this
         * library could not check it, {@link #BIND_MISMATCH} that the reading application is wired wrong,
         * and the five {@code SCHEMA_*} fetch codes that no schema was obtained to check against. Nothing
         * about the document is being asserted by any of them, which is what a caller routing on the answer
         * needs to know.
         *
         * <p><b>A §8.2 name-hygiene refusal is a verdict</b>, though not a validity one: the processor
         * looked and declined, and the sender holds the fix. What it is not is an {@link #SCHEMA_ERROR}-style
         * claim about conformance, which is what the code beside it carries.
         *
         * <p>Stated here so that a consumer does not keep its own copy of the set. Two already would --
         * {@code TsonCli.exitCodeFor} and this project's HTTP surface -- and a private copy each is how two
         * consumers come to disagree about the same diagnostic.
         */
        public boolean verdict() {
            return switch (this) {
                case NOT_IMPLEMENTED, BIND_MISMATCH, SCHEMA_NOT_PERMITTED, SCHEMA_NOT_FOUND,
                        SCHEMA_UNREACHABLE, SCHEMA_TIMEOUT, SCHEMA_TOO_LARGE -> false;
                default -> true;
            };
        }
    }
}
