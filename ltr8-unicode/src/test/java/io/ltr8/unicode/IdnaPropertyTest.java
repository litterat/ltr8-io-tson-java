package io.ltr8.unicode;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 5892's derived property, computed by its own rules, against the last table IANA published: Unicode 12.0's
 * {@code idna-tables-properties.csv}, carried as its code point and property columns. Every code point 12.0
 * assigns must derive to 12.0's value at this library's Unicode version -- the property is built never to move
 * for an assigned code point -- and only what 12.0 left unassigned may differ.
 *
 * <p>No literal character beyond ASCII appears in this source; each is an escape.
 */
class IdnaPropertyTest {

    @Test
    void everyCodePointTwelveAssignsDerivesToTwelvesValue() throws IOException {
        IdnaProperty[] expected = new IdnaProperty[Character.MAX_CODE_POINT + 1];
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                IdnaPropertyTest.class.getResourceAsStream("idna-tables-12.0.0-properties.csv")),
                StandardCharsets.UTF_8))) {
            in.readLine();
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                String[] fields = line.split(",");
                String[] range = fields[0].split("-");
                int from = Integer.parseInt(range[0], 16);
                int to = range.length > 1 ? Integer.parseInt(range[1], 16) : from;
                for (int cp = from; cp <= to; cp++) {
                    expected[cp] = IdnaProperty.valueOf(fields[1]);
                }
            }
        }
        List<String> differences = new ArrayList<>();
        int compared = 0;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (expected[cp] == null || expected[cp] == IdnaProperty.UNASSIGNED
                    || (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)) {
                continue;
            }
            compared++;
            IdnaProperty derived = IdnaProperty.of(cp);
            if (derived != expected[cp] && differences.size() < 20) {
                differences.add("U+%04X: 12.0 says %s, derived %s".formatted(cp, expected[cp], derived));
            }
        }
        assertEquals(List.of(), differences);
        assertTrue(compared > 270_000, "compared " + compared);
    }

    @Test
    void theAsciiValuesAreLdhAlone() {
        assertEquals(IdnaProperty.PVALID, IdnaProperty.of('a'));
        assertEquals(IdnaProperty.PVALID, IdnaProperty.of('7'));
        assertEquals(IdnaProperty.PVALID, IdnaProperty.of('-'));
        assertEquals(IdnaProperty.DISALLOWED, IdnaProperty.of('A'));
        assertEquals(IdnaProperty.DISALLOWED, IdnaProperty.of('_'));
        assertEquals(IdnaProperty.DISALLOWED, IdnaProperty.of('.'));
    }

    /** Uppercase beyond ASCII is unstable under the fold, so a U-label never holds one. */
    @Test
    void uppercaseIsDisallowedAndItsLowercasePvalid() {
        assertEquals(IdnaProperty.DISALLOWED, IdnaProperty.of('Ü'));
        assertEquals(IdnaProperty.PVALID, IdnaProperty.of('ü'));
        assertEquals(IdnaProperty.DISALLOWED, IdnaProperty.of('ｅ'));
    }

    @Test
    void theJoinersAreContextJ() {
        assertEquals(IdnaProperty.CONTEXTJ, IdnaProperty.of(0x200C));
        assertEquals(IdnaProperty.CONTEXTJ, IdnaProperty.of(0x200D));
    }

    /**
     * Assigned after 12.0 and derived like any other: U+1E290, a Toto letter of Unicode 14.0, is a letter; U+1E030,
     * a Cyrillic modifier letter of 15.0, has a compatibility decomposition and so is unstable.
     */
    @Test
    void aCodePointAssignedSinceTwelveDerivesToo() {
        assertEquals(IdnaProperty.PVALID, IdnaProperty.of(0x1E290));
        assertEquals(IdnaProperty.DISALLOWED, IdnaProperty.of(0x1E030));
        assertEquals(IdnaProperty.UNASSIGNED, IdnaProperty.of(0x0378));
    }

    @Test
    void zwnjNeedsAViramaBeforeOrAJoiningContext() {
        assertTrue(IdnaProperty.contextual("क्‌ष", 2));
        assertTrue(IdnaProperty.contextual("ب‌ب", 1));
        assertFalse(IdnaProperty.contextual("ab‌c", 2));
        assertFalse(IdnaProperty.contextual("‌ab", 0));
    }

    @Test
    void zwjNeedsAViramaBefore() {
        assertTrue(IdnaProperty.contextual("क्‍", 2));
        assertFalse(IdnaProperty.contextual("ب‍ب", 1));
    }

    @Test
    void theContextORules() {
        assertTrue(IdnaProperty.contextual("l·l", 1));
        assertFalse(IdnaProperty.contextual("a·l", 1));
        assertTrue(IdnaProperty.contextual("͵α", 0));
        assertFalse(IdnaProperty.contextual("͵a", 0));
        assertTrue(IdnaProperty.contextual("א׳", 1));
        assertFalse(IdnaProperty.contextual("a׳", 1));
        assertTrue(IdnaProperty.contextual("ア・イ", 1));
        assertFalse(IdnaProperty.contextual("a・b", 1));
        assertTrue(IdnaProperty.contextual("ب١", 1));
        assertFalse(IdnaProperty.contextual("١۱", 0));
        assertFalse(IdnaProperty.contextual("۱١", 0));
    }
}
