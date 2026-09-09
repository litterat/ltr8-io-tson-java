package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomValidationException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidParserTest {

    private static String token(String text) {
        return text;
    }

    @Test
    void acceptsCanonicalLowercaseForm() {
        UUID expected = UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09");
        assertEquals(expected, UuidParser.UNCONSTRAINED.read(token("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09")));
    }

    @Test
    void acceptsUppercaseHexDigits() {
        UUID expected = UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09");
        assertEquals(expected, UuidParser.UNCONSTRAINED.read(token("9F1C8E2A-4B7D-4E6F-9A3B-2C5D8E7F1A09")));
    }

    @Test
    void allZerosIsValid() {
        UUID expected = UUID.fromString("00000000-0000-0000-0000-000000000000");
        assertEquals(expected, UuidParser.UNCONSTRAINED.read(token("00000000-0000-0000-0000-000000000000")));
    }

    // ── UUID.fromString is more lenient than RFC 9562's canonical grouping -- confirm we reject
    // what it would silently accept (empirically verified before writing UuidParser at all). ──

    @Test
    void unpaddedGroupsAreRejectedEvenThoughUuidFromStringAcceptsThem() {
        assertThrows(AtomParseException.class, () -> UuidParser.UNCONSTRAINED.read(token("1-2-3-4-5")));
    }

    @Test
    void shortGroupIsRejectedEvenThoughUuidFromStringAcceptsIt() {
        // One hex digit short in the last group -- UUID.fromString silently reinterprets group
        // boundaries and still succeeds; the canonical-shape check must not.
        assertThrows(AtomParseException.class,
                () -> UuidParser.UNCONSTRAINED.read(token("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a0")));
    }

    @Test
    void noHyphensAtAllIsRejected() {
        assertThrows(AtomParseException.class, () -> UuidParser.UNCONSTRAINED.read(token("9f1c8e2a4b7d4e6f9a3b2c5d8e7f1a09")));
    }

    @Test
    void nonHexCharacterIsRejected() {
        assertThrows(AtomParseException.class,
                () -> UuidParser.UNCONSTRAINED.read(token("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7fzz09")));
    }

    @Test
    void nonUuidTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> UuidParser.UNCONSTRAINED.read(token("not-a-uuid")));
    }

    // ── version constraint (unexercised by the built-in instance, but implemented) ─────────

    @Test
    void versionConstraintAcceptsMatchingVersion() {
        // A real version-4 (random) UUID: the version nibble (13th hex digit) is '4'.
        String v4 = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";
        UuidParser type = new UuidParser(Optional.of(4));
        assertEquals(UUID.fromString(v4), type.read(token(v4)));
    }

    @Test
    void versionConstraintRejectsMismatchedVersion() {
        String v4 = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";
        UuidParser type = new UuidParser(Optional.of(1));
        assertThrows(AtomValidationException.class, () -> type.read(token(v4)));
    }

    // ── read(token, target) via AtomType's default ──────────────────────────────────────────

    @Test
    void readWithMatchingTargetReturnsTheValue() {
        String text = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";
        assertEquals(UUID.fromString(text), UuidParser.UNCONSTRAINED.boundTo(UUID.class).orElseThrow().read(token(text)));
    }

    @Test
    void aTargetTheFamilyDoesNotReachIsRefusedBeforeAnyValue() {
        // Empty rather than a thrown refusal: the answer does not depend on a value, so it is available
        // where the reader is built. A text target is admitted -- this family's wire form is text.
        assertTrue(UuidParser.UNCONSTRAINED.boundTo(Integer.class).isEmpty());
        assertTrue(UuidParser.UNCONSTRAINED.boundTo(UUID.class).isPresent());
        assertTrue(UuidParser.UNCONSTRAINED.boundTo(String.class).isPresent());
    }

    @Test
    void writeRoundTripsThroughRead() {
        String text = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";
        assertEquals(text, UuidParser.UNCONSTRAINED.write(UuidParser.UNCONSTRAINED.read(token(text))));
    }
}
