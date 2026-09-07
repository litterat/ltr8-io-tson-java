package io.ltr8.tson.compiler;

import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.*;

import java.util.Optional;

/**
 * Turns a thrown failure into a {@link Diagnostic} -- the TSON text encoding's own classifiers.
 *
 * <p><b>Why these are not on {@code Diagnostic} itself.</b> Every method here switches on an exception
 * type this engine declares: {@code ParseException}, {@code LexException}, {@code
 * TsonUnsupportedDocumentException}, {@code LimitExceededException}, {@code
 * SchemaFetchException}. A {@code Diagnostic} is the shape of an answer and is shared by every
 * encoding ([TSON-JSON] §9.4 reports in the same four categories and adds none); <em>classifying</em> a
 * failure is reading a document, which is each encoding's own. Leaving these on the record would have
 * made {@code tson-base} depend on the TSON engine, or made one switch responsible for exceptions it
 * cannot name -- which is how {@code ofBaseSyntaxError} came to rethrow anything it did not recognise,
 * including another encoding's syntax error.
 *
 * <p>Public for the same reason {@code ofBaseSyntaxError} always was: one of the three base-syntax
 * exception types lives in the unexported {@code lexer} package and cannot be named in a {@code catch}
 * from another module, so a caller driving the parser directly cannot make this classification
 * themselves.
 */
public final class TsonDiagnostics {

    private TsonDiagnostics() {
    }

    /**
     * {@code e} as a base-syntax {@link Diagnostic.Code#VALIDATION_ERROR} -- the three ways a document can fail before
     * any reader sees a value: it doesn't lex, it doesn't parse, or it's a well-formed document of a kind
     * this parser doesn't implement (§8.1 requires that last distinction be visible rather than folded into
     * "malformed").
     *
     * <p><b>Anything else is rethrown, deliberately</b> -- including {@link LimitExceededException},
     * which has its own classifier ({@link Diagnostic#ofLimitExceeded}) and is caught ahead of this. {@code
     * Tson.validate} promises never to throw for a
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
            case ParseException p -> {
                position = p.position();
                // A parse failure that named a construct carries the pair itself; one that stated a rule
                // (an adjacency violation, a missing separator) has no substitution to describe.
                if (!p.expected().isEmpty()) {
                    expected = p.expected();
                    actual = p.actual();
                }
            }
            case io.ltr8.tson.compiler.lexer.LexException l -> position = l.position();
            case TsonUnsupportedDocumentException u -> position = u.position();
            default -> throw e;
        }
        return new Diagnostic(Optional.of(""), Optional.empty(), "", Diagnostic.Code.VALIDATION_ERROR, e.getMessage(),
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
     * <p>The code stays {@link Diagnostic.Code#VALIDATION_ERROR}, the base-syntax code: where the failure is found is
     * carried by the location components, which is the whole reason there are four of them.
     *
     * @param declaration the declaration the error was found in, or {@code ""} for one outside any -- a
     *                    malformed header, an unterminated map -- which makes the pointer a <em>present</em>
     *                    {@code ""}, RFC 6901's own spelling of "the whole document"
     */
    public static Diagnostic ofSchemaSyntaxError(String schemaId, String declaration, ParseException e) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, Diagnostic.Code.VALIDATION_ERROR, e.getMessage(),
                e.expected().isEmpty() ? "well-formed TSON" : e.expected(),
                e.actual().isEmpty() ? "a syntax error" : e.actual(),
                Optional.empty(), Optional.ofNullable(e.position()));
    }

    /**
     * {@link #ofSchemaSyntaxError(String, String, ParseException)} over the three ways a *schema* document
     * can fail before any declaration resolves, classified exactly as {@link #ofBaseSyntaxError} classifies
     * them and rethrowing anything else for the same reason. Lives here for the same reason too: {@code
     * LexException} is in the unexported {@code lexer} package, so a caller in another module cannot catch it.
     */
    public static Diagnostic ofSchemaSyntaxError(String schemaId, RuntimeException e) {
        SourcePosition position;
        switch (e) {
            case ParseException p -> {
                return ofSchemaSyntaxError(schemaId, "", p);
            }
            case io.ltr8.tson.compiler.lexer.LexException l -> position = l.position();
            case TsonUnsupportedDocumentException u -> position = u.position();
            default -> throw e;
        }
        return new Diagnostic(Optional.empty(), Optional.of(""), schemaId, Diagnostic.Code.VALIDATION_ERROR, e.getMessage(),
                "well-formed TSON", "a syntax error", Optional.empty(), Optional.ofNullable(position));
    }

    /**
     * The schema could not be obtained at all -- {@link #ofSchemaError}'s shape with {@link
     * Diagnostic.Code#of(SchemaFetchException.Reason) fetch code} in place of {@code SCHEMA_ERROR}, and the whole
     * of the difference between
     * "this schema is wrong" and "nobody would give me this schema".
     *
     * <p><b>It takes the exception rather than its message</b>, because it is built from a {@link
     * SchemaFetchException} and from nothing else -- which is what makes the distinction cheap: {@link
     * SchemaSource#fetch} names that type for "cannot supply this", so the two cases never have to be
     * told apart by reading a message. An {@code !!import} or {@code !!meta} naming an identity no source
     * will serve reaches this; one that resolves and then fails to link is a {@code SCHEMA_ERROR} like any
     * other.
     *
     * <p>Taking the whole exception is also what picks the {@link Diagnostic.Code}, and what fills the {@code
     * expected}/{@code actual} pair: the reference that could not be obtained is {@code actual}, since it is
     * the thing a consumer must look at. The alternative -- passing a flattened message -- is how this
     * factory came to state {@link SchemaFetchException.Reason}'s distinction nowhere.
     */
    public static Diagnostic ofSchemaUnavailable(String schemaId, String declaration,
                                                 SchemaFetchException e, Optional<SourcePosition> position) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, Diagnostic.Code.of(e.reason()), e.getMessage(), SchemaFailure.UNAVAILABLE_EXPECTED, e.uri(),
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
                schemaId, Diagnostic.Code.SCHEMA_ERROR, message, "", "", Optional.empty(), position);
    }

    /**
     * A [TSON-DATA] §8.2 name-hygiene <b>refusal</b> at one declaration -- {@link #ofSchemaError}'s shape
     * with a policy code in place of {@code SCHEMA_ERROR}. The schema is not wrong: it is refused by this
     * processor, under a policy reading data the Unicode Consortium declines to freeze, and §8.2 says such
     * a refusal MUST NOT be reported in any of §8.1's four categories.
     *
     * <p>The peer of what a read reports for the same rule, so one schema and one document that break the
     * same rule come back under the same code. {@code code} is {@link Diagnostic.Code#CONFUSABLE_NAMES} for names that
     * read alike, {@link Diagnostic.Code#RESTRICTED_CHARACTER} for a character outside the identifier profile and {@link
     * Diagnostic.Code#RESTRICTED_SCRIPT} for a script combination the restriction level does not admit -- the caller picks,
     * because the rule is what it knows and the code is what a consumer routes on. One code per rule, so the
     * code alone tells an author which fix applies.
     *
     * <p><b>It is still a verdict on the schema</b>, and a consumer treats it as one: the document must
     * change, or the deployment must relax the policy in code. What it is not is a claim that the format
     * says the schema is malformed, which is why it does not ride {@code SCHEMA_ERROR}.
     */
    public static Diagnostic ofSchemaRefusal(String schemaId, String declaration, Diagnostic.Code code, String message,
                                             Optional<SourcePosition> position) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, code, message, "", "", Optional.empty(), position);
    }

    /**
     * A schema and a class bound to it that cannot work together -- {@link #ofSchemaError}'s shape with
     * {@link Diagnostic.Code#BIND_MISMATCH} in place of {@code SCHEMA_ERROR}, and the difference between "your schema is
     * wrong" and "this application is wired wrong".
     *
     * <p>Built from a {@link BindMismatchException} and from nothing else. It exists so a collecting
     * caller hears about a wiring mistake in the same list as everything else rather than having it thrown
     * past them as though it were a fault in this library: the schema may be perfectly good, and the message
     * names one of the caller's own classes.
     */
    public static Diagnostic ofSchemaBindMismatch(String schemaId, String declaration,
                                                  BindMismatchException e,
                                                  Optional<SourcePosition> position) {
        return new Diagnostic(Optional.empty(), Optional.of(declaration.isEmpty() ? "" : "/" + declaration),
                schemaId, Diagnostic.Code.BIND_MISMATCH, e.getMessage(), "", "", Optional.empty(), position);
    }

    /**
     * A construct this library has not implemented, found at one declaration -- {@link #ofSchemaError}'s
     * shape with {@link Diagnostic.Code#NOT_IMPLEMENTED} in place of {@code SCHEMA_ERROR}, and the whole of the
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
                schemaId, Diagnostic.Code.NOT_IMPLEMENTED, message, "", "", Optional.empty(), position);
    }

}
