package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.base.unicode.Normalization;
import io.ltr8.tson.schema.meta.TextType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextParserTest {

    private static String token(String text) {
        return text;
    }

    @Test
    void unconstrainedAcceptsAnyText() {
        assertEquals("hello", TextParser.UNCONSTRAINED.read(token("hello")));
        assertEquals("", TextParser.UNCONSTRAINED.read(token("")));
    }

    @Test
    void exactLengthRejectsWrongLength() {
        TextParser type = new TextParser(Optional.empty(), Optional.empty(), Optional.of(5), Optional.empty());
        assertEquals("hello", type.read(token("hello")));
        assertThrows(AtomValidationException.class, () -> type.read(token("hi")));
    }

    @Test
    void minLengthRejectsShorterText() {
        TextParser type = new TextParser(Optional.of(3), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals("abc", type.read(token("abc")));
        assertThrows(AtomValidationException.class, () -> type.read(token("ab")));
    }

    @Test
    void maxLengthRejectsLongerText() {
        TextParser type = new TextParser(Optional.empty(), Optional.of(3), Optional.empty(), Optional.empty());
        assertEquals("abc", type.read(token("abc")));
        assertThrows(AtomValidationException.class, () -> type.read(token("abcd")));
    }

    @Test
    void patternRejectsNonMatchingText() {
        TextParser type = new TextParser(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("[a-z]+"));
        assertEquals("abc", type.read(token("abc")));
        assertThrows(AtomValidationException.class, () -> type.read(token("ABC")));
    }

    @Test
    void patternMatchesWithIRegexpSemantics() {
        // The pattern constraint is matched through tson-regex (RFC 9485 I-Regexp), so a Unicode category
        // class works with I-Regexp's own \p{...} semantics rather than java.util.regex's.
        TextParser type = new TextParser(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("\\p{Nd}+"));
        assertEquals("2026", type.read(token("2026")));
        assertThrows(AtomValidationException.class, () -> type.read(token("12a")));
    }

    @Test
    void writeRoundTripsThroughRead() {
        assertEquals("hello", TextParser.UNCONSTRAINED.write(TextParser.UNCONSTRAINED.read(token("hello"))));
    }

    /** The value is the text in the type's form, and the facets judge that value ([TSON-SCHEMA] §5.5). */
    @Test
    void theValueIsTheTextInTheTypesNormalizationForm() {
        TextParser charset = new TextParser(new TextType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("[a-z0-9-]+"), Optional.of(List.of("UTF-8", "us-ascii")), Normalization.NFKC_CASEFOLD));
        assertEquals("utf-8", charset.read(token("Utf-8")));
        assertEquals("us-ascii", charset.read(token("US-ASCII")));
        assertThrows(AtomValidationException.class, () -> charset.read(token("Latin1")));
    }

    @Test
    void noNormalizationKeepsTheTextAsWritten() {
        assertEquals("Content-Type", TextParser.UNCONSTRAINED.read(token("Content-Type")));
    }

    /** A length counts code points: a character outside the Basic Multilingual Plane is one, not two UTF-16 units. */
    @Test
    void aLengthCountsCodePoints() {
        String grin = "\uD83D\uDE00";
        TextParser one = new TextParser(Optional.empty(), Optional.of(1), Optional.empty(), Optional.empty());
        assertEquals(grin, one.read(token(grin)));
        assertThrows(AtomValidationException.class, () -> one.read(token("a" + grin)));
        TextParser exactlyTwo = new TextParser(Optional.empty(), Optional.empty(), Optional.of(2), Optional.empty());
        assertEquals("a" + grin, exactlyTwo.read(token("a" + grin)));
        TextParser atLeastTwo = new TextParser(Optional.of(2), Optional.empty(), Optional.empty(), Optional.empty());
        assertThrows(AtomValidationException.class, () -> atLeastTwo.read(token(grin)));
    }
}
