package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.base.SourcePosition;

import java.util.Optional;

/**
 * Turns a thrown failure into a {@link Diagnostic} -- the JSON encoding's own classifier.
 *
 * <p><b>Each encoding owns the switch over its own exceptions</b>, which is why this exists beside
 * {@code TsonDiagnostics} rather than being a case inside it. A {@link Diagnostic} is the shape of an
 * answer and is shared ([TSON-JSON] §9.4 reports in [TSON-DATA] §8.1's four categories and adds none of
 * its own); <em>classifying</em> a failure is reading a document, and reading is where the two encodings
 * are separate stacks. One switch responsible for both would be a switch responsible for exceptions it
 * cannot name.
 *
 * <p><b>One case where the text encoding has three.</b> [TSON-DATA] §8.1 makes a lexer error and a parse
 * error separate categories and TSON text keeps them separate in the type; the JSON stack raises {@link
 * ParseException} for both, so the split is made here, when a failure becomes a diagnostic, rather than
 * carried on the exception where a second opinion could only disagree with it. There is no counterpart to
 * {@code TsonUnsupportedDocumentException} either: a JSON document declares no conformance class to be
 * refused for, {@code !!meta} being TSON text syntax.
 */
public final class JsonDiagnostics {

    private JsonDiagnostics() {
    }

    /**
     * {@code e} as a base-syntax {@link Diagnostic.Code#VALIDATION_ERROR} -- a document that will not parse,
     * whether it failed RFC 8259's grammar, [TSON-JSON] §3.1's profile on top of it, or UTF-8 decoding
     * underneath.
     *
     * <p><b>{@code expected} names the encoding that refused.</b> A caller routing on a diagnostic can hold
     * documents in either encoding, and "well-formed JSON" is the difference between a sender who reaches
     * for their JSON writer and one who reaches for the wrong one.
     *
     * <p><b>Anything else is rethrown, deliberately</b> -- including {@link
     * io.ltr8.tson.base.LimitExceededException}, which has its own classifier ({@link
     * Diagnostic#ofLimitExceeded}) and is caught ahead of this. An exception that is not a syntax failure is
     * a fault in this library, and turning it into a diagnostic would tell a caller their document is
     * invalid when it is not, burying the real failure and its stack trace behind a false verdict.
     * Classifying or rethrowing is one decision, so it is made here rather than handed back as an {@code
     * Optional} every call site unwraps the same way.
     *
     * <p><b>The readers call this for you.</b> Every whole-document entry point on {@link JsonTreeReader}/
     * {@link JsonObjectReader} routes a base-syntax failure through the read's own receiver, so a collecting
     * read gets it as a diagnostic and a fail-fast one throws from the receiver. What is left for a caller is
     * driving a {@code JsonStream} directly, where there is no receiver to route through.
     */
    public static Diagnostic ofBaseSyntaxError(RuntimeException e) {
        SourcePosition position;
        String expected = "well-formed JSON";
        String actual = "a base-syntax error";
        switch (e) {
            case ParseException p -> {
                position = p.position();
                // A refusal that named a construct carries the pair itself; one that stated a rule (malformed
                // UTF-8, a duplicate member name) has no substitution to describe.
                if (!p.expected().isEmpty()) {
                    expected = p.expected();
                    actual = p.actual();
                }
            }
            default -> throw e;
        }
        return new Diagnostic(Optional.of(""), Optional.empty(), "", Diagnostic.Code.VALIDATION_ERROR,
                e.getMessage(), expected, actual, Optional.ofNullable(position), Optional.empty());
    }
}
