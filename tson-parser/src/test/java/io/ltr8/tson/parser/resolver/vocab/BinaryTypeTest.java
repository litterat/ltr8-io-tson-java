package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryTypeTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    // ── BASE64 ───────────────────────────────────────────────────────────────

    @Test
    void base64DecodesCorrectlyPaddedInput() {
        assertArrayEquals("Man".getBytes(StandardCharsets.UTF_8), BinaryType.BASE64.read(token("TWFu")));
    }

    @Test
    void base64DecodesWithSinglePaddingCharacter() {
        assertArrayEquals("Ma".getBytes(StandardCharsets.UTF_8), BinaryType.BASE64.read(token("TWE=")));
    }

    @Test
    void base64DecodesWithDoublePaddingCharacter() {
        assertArrayEquals("M".getBytes(StandardCharsets.UTF_8), BinaryType.BASE64.read(token("TQ==")));
    }

    @Test
    void base64EmptyStringDecodesToEmptyArray() {
        assertArrayEquals(new byte[0], BinaryType.BASE64.read(token("")));
    }

    @Test
    void base64MissingPaddingIsRejected() {
        // java.util.Base64.getDecoder() alone would accept "TWE" -- our own length check must not.
        assertThrows(AtomParseException.class, () -> BinaryType.BASE64.read(token("TWE")));
    }

    @Test
    void base64UrlSafeCharactersAreRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.BASE64.read(token("TWE_-==")));
    }

    @Test
    void base64NonAlphabetCharacterIsRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.BASE64.read(token("!!!!")));
    }

    @Test
    void base64RoundTripsArbitraryBytes() {
        byte[] original = {0, 1, 2, (byte) 0xFF, (byte) 0x80, 127, -128};
        String encoded = Base64.getEncoder().encodeToString(original);
        assertArrayEquals(original, BinaryType.BASE64.read(token(encoded)));
    }

    // ── BASE64URL ────────────────────────────────────────────────────────────

    @Test
    void base64UrlDecodesUrlSafeAlphabet() {
        // Bytes chosen so the standard alphabet would need '+'/'/' -- forces '-'/'_' in the url form.
        byte[] original = {(byte) 0xFB, (byte) 0xFF, (byte) 0xBE};
        String urlEncoded = Base64.getUrlEncoder().encodeToString(original);
        assertArrayEquals(original, BinaryType.BASE64URL.read(token(urlEncoded)));
    }

    @Test
    void base64UrlStandardAlphabetCharactersAreRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.BASE64URL.read(token("+/==")));
    }

    @Test
    void base64UrlMissingPaddingIsRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.BASE64URL.read(token("TWE")));
    }

    // ── HEX ──────────────────────────────────────────────────────────────────

    @Test
    void hexDecodesLowercase() {
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                BinaryType.HEX.read(token("deadbeef")));
    }

    @Test
    void hexDecodesUppercase() {
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                BinaryType.HEX.read(token("DEADBEEF")));
    }

    @Test
    void hexEmptyStringDecodesToEmptyArray() {
        assertArrayEquals(new byte[0], BinaryType.HEX.read(token("")));
    }

    @Test
    void hexOddLengthIsRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.HEX.read(token("abc")));
    }

    @Test
    void hexNonHexCharacterIsRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.HEX.read(token("zz")));
    }

    // ── BASE32 ───────────────────────────────────────────────────────────────

    // RFC 4648 §10's own base32 test vectors -- the strongest possible confidence check for a
    // from-scratch decoder with no JDK equivalent to cross-check against.
    @ParameterizedTest
    @CsvSource({
            "'', ''",
            "f, MY======",
            "fo, MZXQ====",
            "foo, MZXW6===",
            "foob, MZXW6YQ=",
            "fooba, MZXW6YTB",
            "foobar, MZXW6YTBOI======"
    })
    void base32Rfc4648TestVectors(String decoded, String encoded) {
        assertArrayEquals(decoded.getBytes(StandardCharsets.UTF_8), BinaryType.BASE32.read(token(encoded)));
    }

    @Test
    void base32LowercaseIsRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.BASE32.read(token("my======")));
    }

    @Test
    void base32WrongLengthIsRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.BASE32.read(token("MY=====")));
    }

    @Test
    void base32IllegalPaddingCountIsRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.BASE32.read(token("MZXW6Y==")));
    }

    @Test
    void base32AllPaddingCharactersIsRejected() {
        // Regression check for the padding-count array bounds bug caught while writing this.
        assertThrows(AtomParseException.class, () -> BinaryType.BASE32.read(token("========")));
    }

    @Test
    void base32NonAlphabetCharacterIsRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.BASE32.read(token("MY1=====")));
    }

    @Test
    void base32PaddingInTheMiddleIsRejected() {
        assertThrows(AtomParseException.class, () -> BinaryType.BASE32.read(token("MZ=W6YTB")));
    }

    // ── min_length / max_length (unexercised by any built-in, but implemented) ──────────────

    @Test
    void minLengthRejectsShorterDecodedValue() {
        BinaryType type = new BinaryType(BinaryType.Encoding.HEX, Optional.of(4), Optional.empty());
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}, type.read(token("deadbeef")));
        assertThrows(AtomValidationException.class, () -> type.read(token("dead")));
    }

    @Test
    void maxLengthRejectsLongerDecodedValue() {
        BinaryType type = new BinaryType(BinaryType.Encoding.HEX, Optional.empty(), Optional.of(2));
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD}, type.read(token("dead")));
        assertThrows(AtomValidationException.class, () -> type.read(token("deadbeef")));
    }

    // ── write() ──────────────────────────────────────────────────────────

    @Test
    void writeBase64AlwaysPads() {
        byte[] value = {0, 1, 2, (byte) 0xFF, (byte) 0x80, 127, -128};
        String written = BinaryType.BASE64.write(value);
        assertArrayEquals(value, BinaryType.BASE64.read(token(written)));
        assertEquals(0, written.length() % 4);
    }

    @Test
    void writeBase64UrlUsesTheUrlSafeAlphabet() {
        byte[] value = {(byte) 0xFB, (byte) 0xFF, (byte) 0xBE};
        String written = BinaryType.BASE64URL.write(value);
        assertArrayEquals(value, BinaryType.BASE64URL.read(token(written)));
    }

    @Test
    void writeHexRoundTrips() {
        byte[] value = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        assertArrayEquals(value, BinaryType.HEX.read(token(BinaryType.HEX.write(value))));
    }

    // Same RFC 4648 §10 test vectors as the decode side, exercised in the encode direction --
    // Base32Decoding#encode has no JDK equivalent to cross-check against either.
    @ParameterizedTest
    @CsvSource({
            "'', ''",
            "f, MY======",
            "fo, MZXQ====",
            "foo, MZXW6===",
            "foob, MZXW6YQ=",
            "fooba, MZXW6YTB",
            "foobar, MZXW6YTBOI======"
    })
    void writeBase32Rfc4648TestVectors(String decoded, String encoded) {
        assertEquals(encoded, BinaryType.BASE32.write(decoded.getBytes(StandardCharsets.UTF_8)));
    }
}
