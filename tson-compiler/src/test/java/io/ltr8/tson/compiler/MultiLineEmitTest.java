package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.lexer.Lexer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonDataEmitter#multiLineString} against the lexer that has to read it back.
 *
 * <p>§7.2.3 is read in a fixed order -- find the closing delimiter, compute the common prefix from its
 * indent narrowed by each non-blank line, strip that prefix, strip each line's trailing whitespace, then
 * decode escapes -- and every case here is a value that order would damage if the emitter wrote it
 * literally. The property is one line: whatever goes in comes back.
 */
class MultiLineEmitTest {

    /** The document this emitter writes for {@code value}. */
    private static String emit(String value) {
        return new TsonDataEmitter().multiLineString(value).toString();
    }

    /** The value the lexer gets back out of that document. */
    private static String roundTrip(String value) {
        Lexer lexer = new Lexer(new ByteArrayInputStream(emit(value).getBytes(StandardCharsets.UTF_8)));
        lexer.nextToken();
        return lexer.text();
    }

    @Test
    void anOrdinaryMultiLineValueComesBackUnchanged() {
        assertEquals("first line\nsecond line", roundTrip("first line\nsecond line"));
    }

    /** It is genuinely written multi-line, not quietly escaped onto one. */
    @Test
    void theEmittedFormIsTheMultiLineOne() {
        String document = emit("a\nb");

        assertTrue(document.startsWith("\"\"\"\n"), document);
        assertTrue(document.endsWith("\n\"\"\""), document);
        assertTrue(document.contains("\na\nb\n"), "the lines are written literally: " + document);
    }

    /** Trailing whitespace is stripped per line before escapes decode, so it has to survive as an escape. */
    @Test
    void trailingWhitespaceSurvivesTheStrip() {
        assertEquals("padded   \ntabbed\t", roundTrip("padded   \ntabbed\t"));
    }

    /** A line that is only spaces is the same problem with nothing else on the line. */
    @Test
    void aLineOfOnlySpacesSurvives() {
        assertEquals("a\n   \nb", roundTrip("a\n   \nb"));
    }

    /** An empty line is genuinely empty and must stay that way. */
    @Test
    void emptyLinesSurvive() {
        assertEquals("a\n\n\nb", roundTrip("a\n\n\nb"));
        assertEquals("\na\n", roundTrip("\na\n"));
    }

    /** Escapes decode on the way back, so a literal backslash has to be doubled going out. */
    @Test
    void aLiteralBackslashIsNotDecodedAway() {
        assertEquals("a\\nb", roundTrip("a\\nb"));
        assertEquals("\\u0041", roundTrip("\\u0041"));
    }

    /** A line the reader would take for the closing delimiter must not end the token early. */
    @Test
    void aLineBeginningWithTheDelimiterDoesNotCloseTheToken() {
        assertEquals("before\n\"\"\"\nafter", roundTrip("before\n\"\"\"\nafter"));
        assertEquals("  \"\"\" indented", roundTrip("  \"\"\" indented"));
    }

    /** A quote that is not a delimiter stays literal -- the form exists to avoid escaping those. */
    @Test
    void ordinaryQuotesStayLiteral() {
        assertEquals("she said \"hello\"", roundTrip("she said \"hello\""));
    }

    /** Leading whitespace is part of the value: the prefix is empty, so nothing is stripped from the front. */
    @Test
    void leadingIndentationIsPartOfTheValue() {
        assertEquals("no indent\n    four spaces\n\ttab", roundTrip("no indent\n    four spaces\n\ttab"));
    }

    /** A carriage return would be a line terminator to the lexer, so it cannot be written literally. */
    @Test
    void aCarriageReturnDoesNotBecomeALineBreak() {
        assertEquals("a\rb", roundTrip("a\rb"));
    }

    /** Non-ASCII is written literally -- not escaping it is the whole point of the form. */
    @Test
    void nonAsciiIsWrittenLiterally() {
        String document = emit("héllo\nwörld");

        assertTrue(document.contains("héllo"), document);
        assertEquals("héllo\nwörld", roundTrip("héllo\nwörld"));
    }

    @Test
    void anEmptyValueRoundTrips() {
        assertEquals("", roundTrip(""));
    }
}
