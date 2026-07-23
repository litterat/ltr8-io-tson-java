package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UuidTypeTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsCanonicalLowercaseForm() {
        UUID expected = UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09");
        assertEquals(expected, UuidType.UNCONSTRAINED.read(token("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09")));
    }

    @Test
    void acceptsUppercaseHexDigits() {
        UUID expected = UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09");
        assertEquals(expected, UuidType.UNCONSTRAINED.read(token("9F1C8E2A-4B7D-4E6F-9A3B-2C5D8E7F1A09")));
    }

    @Test
    void allZerosIsValid() {
        UUID expected = UUID.fromString("00000000-0000-0000-0000-000000000000");
        assertEquals(expected, UuidType.UNCONSTRAINED.read(token("00000000-0000-0000-0000-000000000000")));
    }

    // ── UUID.fromString is more lenient than RFC 9562's canonical grouping -- confirm we reject
    // what it would silently accept (empirically verified before writing UuidType at all). ──

    @Test
    void unpaddedGroupsAreRejectedEvenThoughUuidFromStringAcceptsThem() {
        assertThrows(AtomParseException.class, () -> UuidType.UNCONSTRAINED.read(token("1-2-3-4-5")));
    }

    @Test
    void shortGroupIsRejectedEvenThoughUuidFromStringAcceptsIt() {
        // One hex digit short in the last group -- UUID.fromString silently reinterprets group
        // boundaries and still succeeds; the canonical-shape check must not.
        assertThrows(AtomParseException.class,
                () -> UuidType.UNCONSTRAINED.read(token("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a0")));
    }

    @Test
    void noHyphensAtAllIsRejected() {
        assertThrows(AtomParseException.class, () -> UuidType.UNCONSTRAINED.read(token("9f1c8e2a4b7d4e6f9a3b2c5d8e7f1a09")));
    }

    @Test
    void nonHexCharacterIsRejected() {
        assertThrows(AtomParseException.class,
                () -> UuidType.UNCONSTRAINED.read(token("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7fzz09")));
    }

    @Test
    void nonUuidTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> UuidType.UNCONSTRAINED.read(token("not-a-uuid")));
    }

    // ── version constraint (unexercised by the built-in instance, but implemented) ─────────

    @Test
    void versionConstraintAcceptsMatchingVersion() {
        // A real version-4 (random) UUID: the version nibble (13th hex digit) is '4'.
        String v4 = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";
        UuidType type = new UuidType(Optional.of(4));
        assertEquals(UUID.fromString(v4), type.read(token(v4)));
    }

    @Test
    void versionConstraintRejectsMismatchedVersion() {
        String v4 = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";
        UuidType type = new UuidType(Optional.of(1));
        assertThrows(AtomValidationException.class, () -> type.read(token(v4)));
    }

    // ── read(token, target) via AtomType's default ──────────────────────────────────────────

    @Test
    void readWithMatchingTargetReturnsTheValue() {
        String text = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";
        assertEquals(UUID.fromString(text), UuidType.UNCONSTRAINED.read(token(text), UUID.class));
    }

    @Test
    void readWithMismatchedTargetThrows() {
        String text = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";
        assertThrows(AtomValidationException.class, () -> UuidType.UNCONSTRAINED.read(token(text), String.class));
    }

    @Test
    void writeRoundTripsThroughRead() {
        String text = "9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09";
        assertEquals(text, UuidType.UNCONSTRAINED.write(UuidType.UNCONSTRAINED.read(token(text))));
    }
}
