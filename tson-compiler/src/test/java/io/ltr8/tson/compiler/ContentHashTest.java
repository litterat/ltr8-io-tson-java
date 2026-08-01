package io.ltr8.tson.compiler;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Content hashing per [TSON-DATA] §2.2.1: SHA-256 (lowercase hex) of every byte after the first line's
 * terminator, the id line excluded. Checked against known SHA-256 values so the byte boundary is pinned.
 */
class ContentHashTest {

    // Well-known SHA-256 digests.
    private static final String SHA_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String SHA_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void hashesEveryByteAfterTheFirstLineTerminator() {
        assertEquals(SHA_ABC, ContentHash.sha256(utf8("!!id:\"x\"\nabc")));
    }

    @Test
    void anEmptyBodyHashesToTheEmptyDigest() {
        assertEquals(SHA_EMPTY, ContentHash.sha256(utf8("!!id:\"x\"\n")));
    }

    @Test
    void crlfHashInputBeginsAfterTheLf() {
        assertEquals(SHA_ABC, ContentHash.sha256(utf8("!!id:\"x\"\r\nabc")));
    }

    @Test
    void aLoneCrTerminatesTheFirstLineToo() {
        assertEquals(SHA_ABC, ContentHash.sha256(utf8("!!id:\"x\"\rabc")));
    }

    @Test
    void aLeadingBomIsStrippedAndNeverHashed() {
        assertEquals(SHA_ABC, ContentHash.sha256(utf8("\uFEFF!!id:\"x\"\nabc")));
    }

    @Test
    void theIdLineContentDoesNotAffectTheHash() {
        // Same body after the terminator -> same hash, whatever the (excluded) id line says.
        assertEquals(ContentHash.sha256(utf8("!!id:\"a\"\nbody")),
                ContentHash.sha256(utf8("!!id:\"b?sha256=deadbeef\"\nbody")));
    }

    @Test
    void contentStartPointsPastTheFirstLine() {
        assertEquals(9, ContentHash.contentStart(utf8("!!id:\"x\"\nabc")));   // "!!id:\"x\"\n" is 9 bytes
        assertEquals(12, ContentHash.contentStart(utf8("\uFEFF!!id:\"x\"\nabc")));   // + 3-byte BOM
    }

    @Test
    void noTerminatorAfterTheFirstLineThrows() {
        assertThrows(IllegalArgumentException.class, () -> ContentHash.sha256(utf8("!!id:\"x\"")));
    }

    @Test
    void declaredSha256ExtractsAPinOrEmpty() {
        assertEquals(Optional.of(SHA_ABC), ContentHash.declaredSha256("https://x/s.tn?sha256=" + SHA_ABC));
        assertEquals(Optional.empty(), ContentHash.declaredSha256("https://x/s.tn"));
    }

    @Test
    void declaredSha256RejectsAnUnrecognizedQueryParameter() {
        assertThrows(IllegalArgumentException.class, () -> ContentHash.declaredSha256("https://x/s.tn?md5=abc"));
    }

    @Test
    void declaredSha256RejectsAMalformedPin() {
        assertThrows(IllegalArgumentException.class, () -> ContentHash.declaredSha256("https://x/s.tn?sha256=tooshort"));
        assertThrows(IllegalArgumentException.class,   // uppercase is not full lowercase hex
                () -> ContentHash.declaredSha256("https://x/s.tn?sha256=" + "A".repeat(64)));
    }

    @Test
    void verifyPassesAMatchingPinAndRejectsAMismatch() {
        byte[] doc = utf8("!!id:\"x\"\nabc");   // body "abc" hashes to SHA_ABC
        ContentHash.verify(doc, "https://x/s.tn?sha256=" + SHA_ABC);   // no throw
        assertThrows(ContentHashMismatchException.class,
                () -> ContentHash.verify(doc, "https://x/s.tn?sha256=" + "0".repeat(64)));
    }

    @Test
    void verifyIsANoOpForAnUnpinnedReference() {
        ContentHash.verify(utf8("!!id:\"x\"\nabc"), "https://x/s.tn");   // no throw
    }
}
