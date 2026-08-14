package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code mac} (§5.5, EUI-48 per RFC 9542): six hex octets separated consistently by {@code :} or by
 * {@code -}. core.tn nominates no canonical form, so the authored text comes back unchanged.
 */
class MacParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "00-1B-63-84-45-E6",        // hyphen form -- unquoted-safe, which is why core.tn mentions it
            "00:1B:63:84:45:E6",        // colon form -- must be quoted on the wire, same value here
            "aa-bb-cc-dd-ee-ff",        // lowercase hex
            "Aa-bB-Cc-dD-Ee-fF",        // mixed case
            "FF:FF:FF:FF:FF:FF",        // broadcast
            "00-00-00-00-00-00"})
    void acceptsBothSeparatorFormsInAnyHexCase(String text) {
        assertEquals(text, MacParser.UNCONSTRAINED.read(token(text)));
    }

    /** No canonical form is nominated, so normalising would be this implementation inventing one. */
    @Test
    void returnsTheAuthoredTextRatherThanCanonicalising() {
        assertEquals("aa-BB-cc-DD-ee-FF", MacParser.UNCONSTRAINED.read(token("aa-BB-cc-DD-ee-FF")));
        assertEquals("aa-BB-cc-DD-ee-FF", MacParser.UNCONSTRAINED.write("aa-BB-cc-DD-ee-FF"));
    }

    /** The two forms are alternatives, not a per-octet separator choice. */
    @Test
    void rejectsMixedSeparators() {
        assertThrows(AtomParseException.class, () -> MacParser.UNCONSTRAINED.read(token("00-1B:63-84:45-E6")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "00-1B-63-84-45",           // five octets
            "00-1B-63-84-45-E6-77",     // seven
            "00-1B-63-84-45-E",         // short octet
            "00-1B-63-84-45-E66",       // long octet
            "001B.6384.45E6",           // Cisco dotted-quad form, not EUI-48 as core.tn describes it
            "00 1B 63 84 45 E6",        // space-separated
            "GG-1B-63-84-45-E6",        // non-hex
            "001B638445E6",             // unseparated
            ""})
    void rejectsAnythingElse(String text) {
        assertThrows(AtomParseException.class, () -> MacParser.UNCONSTRAINED.read(token(text)));
    }
}
