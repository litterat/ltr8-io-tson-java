package io.ltr8.net;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A media type with its parameters, one value per spelling. */
class MediaTypeTest {

    private static String refused(String text) {
        return assertThrows(MediaTypeSyntaxException.class, () -> MediaType.parse(text)).getMessage();
    }

    @Test
    void theParts() {
        MediaType type = MediaType.parse("application/ld+json");
        assertEquals("application", type.type());
        assertEquals("ld+json", type.subtype());
        assertEquals(Optional.of("json"), type.suffix());
        assertEquals(Map.of(), type.parameters());
        assertEquals(Optional.empty(), MediaType.parse("text/html").suffix());
    }

    /** RFC 9110 §8.3.1's own example: these four are one media type. */
    @Test
    void namesFoldAndAQuotedValueEqualsItsToken() {
        MediaType canonical = MediaType.parse("text/html;charset=utf-8");
        for (String spelling : new String[] {
                "text/html;charset=UTF-8", "Text/HTML;Charset=\"utf-8\"", "text/html; charset=\"utf-8\"",
                "text/html ; charset=utf-8"}) {
            assertEquals(canonical, MediaType.parse(spelling), spelling);
        }
        assertEquals("text/html;charset=utf-8", MediaType.parse("Text/HTML;Charset=\"UTF-8\"").toString());
    }

    @Test
    void aValueOtherThanCharsetKeepsItsCase() {
        assertNotEquals(MediaType.parse("multipart/form-data;boundary=AbC"),
                MediaType.parse("multipart/form-data;boundary=abc"));
    }

    @Test
    void parametersAreAMap() {
        assertEquals(MediaType.parse("text/plain;a=1;b=2"), MediaType.parse("text/plain;b=2;a=1"));
        assertEquals("text/plain;a=1;b=2", MediaType.parse("text/plain;b=2;a=1").toString());
        assertTrue(refused("text/plain;a=1;A=2").contains("twice"));
    }

    @Test
    void aValueThatIsNoTokenIsWrittenQuoted() {
        assertEquals("text/plain;title=\"a b\\\"c\"", MediaType.parse("text/plain;title=\"a b\\\"c\"").toString());
        assertEquals("text/plain;title=\"\"", MediaType.parse("text/plain;title=\"\"").toString());
    }

    @Test
    void refusals() {
        assertTrue(refused("text/*").contains("media range"));
        assertTrue(refused("*/*").contains("media range"));
        assertTrue(refused("texthtml").contains("'/'"));
        assertTrue(refused("text/").contains("1 to 127"));
        assertTrue(refused("-text/html").contains("letter or digit"));
        assertTrue(refused("text/ht ml").contains("';'"));
        assertTrue(refused("text/html;charset").contains("'='"));
        assertTrue(refused("text/html;charset=").contains("no value"));
        assertTrue(refused("text/html;title=\"open").contains("closing"));
        assertTrue(refused("text/hétml").contains("U+00E9"));
        assertTrue(refused("text/html ").contains("whitespace"));
    }
}
