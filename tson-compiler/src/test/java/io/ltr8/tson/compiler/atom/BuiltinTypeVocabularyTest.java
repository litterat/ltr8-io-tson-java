package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinTypeVocabularyTest {

    // The four widths §5.6's table has always carried; the rest of the ladder is below.
    @ParameterizedTest
    @ValueSource(strings = {"int32", "int64", "uint32", "uint64"})
    void publishedFixedWidthIntegersAreRegistered(String name) {
        assertTrue(BuiltinTypeVocabulary.lookup(name).isPresent());
    }

    // The rest of §5.6's ladder, which core.tn defines from the same constructor.
    @ParameterizedTest
    @ValueSource(strings = {
            "int8", "int16", "int32", "int64", "int128", "int256",
            "uint8", "uint16", "uint32", "uint64", "uint128", "uint256",
            "positive_integer", "non_negative_integer", "negative_integer", "non_positive_integer"
    })
    void fullIntegerFamilyIsRegistered(String name) {
        assertTrue(BuiltinTypeVocabulary.lookup(name).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"number", "float32", "float64"})
    void decimalAndFloatAtomsAreRegistered(String name) {
        assertTrue(BuiltinTypeVocabulary.lookup(name).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rational", "complex"})
    void rationalAndComplexAtomsAreRegistered(String name) {
        assertTrue(BuiltinTypeVocabulary.lookup(name).isPresent());
    }

    @org.junit.jupiter.api.Test
    void uuidAtomIsRegistered() {
        assertTrue(BuiltinTypeVocabulary.lookup("uuid").isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"base64", "base64url", "base32", "hex"})
    void binaryAtomsAreRegistered(String name) {
        assertTrue(BuiltinTypeVocabulary.lookup(name).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"date", "time", "datetime", "duration"})
    void temporalAtomsAreRegistered(String name) {
        assertTrue(BuiltinTypeVocabulary.lookup(name).isPresent());
    }

    @org.junit.jupiter.api.Test
    void uriAtomIsRegistered() {
        assertTrue(BuiltinTypeVocabulary.lookup("uri").isPresent());
    }

    @org.junit.jupiter.api.Test
    void ipv4AtomIsRegistered() {
        assertTrue(BuiltinTypeVocabulary.lookup("ipv4").isPresent());
    }

    @org.junit.jupiter.api.Test
    void ipv6AtomIsRegistered() {
        assertTrue(BuiltinTypeVocabulary.lookup("ipv6").isPresent());
    }

    @org.junit.jupiter.api.Test
    void cidr4AtomIsRegistered() {
        assertTrue(BuiltinTypeVocabulary.lookup("cidr4").isPresent());
    }

    @org.junit.jupiter.api.Test
    void cidr6AtomIsRegistered() {
        assertTrue(BuiltinTypeVocabulary.lookup("cidr6").isPresent());
    }

    /**
     * {@code binary} is not a name the vocabulary ever answers to: §5.3 spells out that "there is no generic
     * {@code !binary} annotation", only its four encodings.
     */
    @ParameterizedTest
    @ValueSource(strings = {"not_a_type", "binary"})
    void namesTheVocabularyDoesNotAnswerToAreNotRegistered(String name) {
        assertFalse(BuiltinTypeVocabulary.lookup(name).isPresent());
    }

    @org.junit.jupiter.api.Test
    void macAtomIsRegistered() {
        assertTrue(BuiltinTypeVocabulary.lookup("mac").isPresent());
    }

    /**
     * {@code email} is a §5.5 built-in beside {@code uuid}/{@code ipv4}/{@code mac}, with the same shape
     * core.tn gives it, so the schemaless and schema-driven paths agree about what {@code !email} means.
     */
    @org.junit.jupiter.api.Test
    void emailAtomIsRegistered() {
        assertTrue(BuiltinTypeVocabulary.lookup("email").isPresent());
    }

    /**
     * §5.5's unconstrained text atom. Every token is accepted and the host value is the token's text, so the
     * only thing to assert about the parse is that no token is turned away and no form is re-interpreted --
     * a quoted numeric under {@code !text} is the string, which is §5.5's own stated reason for the atom
     * existing at all when §4.4 already resolves an unannotated token to a string.
     */
    @org.junit.jupiter.api.Test
    void textIsRegistered() {
        assertTrue(BuiltinTypeVocabulary.lookup("text").isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello", "42", "0xFF", "true", "null", "", "  spaced  ", "a\nb"})
    void textAcceptsEveryTokenAndYieldsItsTextUnchanged(String content) {
        AtomType<?> text = BuiltinTypeVocabulary.lookup("text").orElseThrow();

        assertEquals(content, text.read(new TokenValue(content, TokenForm.SINGLE_LINE_QUOTED)));
    }

    /** Form is not meaning (§2.4): the same content quoted or not is the same string. */
    @org.junit.jupiter.api.Test
    void textReadsAQuotedNumericAsTheString() {
        AtomType<?> text = BuiltinTypeVocabulary.lookup("text").orElseThrow();

        assertEquals("42", text.read(new TokenValue("42", TokenForm.SINGLE_LINE_QUOTED)));
        assertEquals("42", text.read(new TokenValue("42", TokenForm.UNQUOTED)));
    }

    @org.junit.jupiter.api.Test
    void annotationNamesAreCaseSensitive() {
        // §5.1: "Annotation names are case-sensitive. Only the exact names listed below are recognised."
        assertTrue(BuiltinTypeVocabulary.lookup("int32").isPresent());
        assertFalse(BuiltinTypeVocabulary.lookup("Int32").isPresent());
        assertFalse(BuiltinTypeVocabulary.lookup("INT32").isPresent());
    }

    @org.junit.jupiter.api.Test
    void registeredEntryActuallyValidatesLikeItsPublishedContract() {
        @SuppressWarnings("unchecked")
        AtomType<Number> int8 = (AtomType<Number>) BuiltinTypeVocabulary.lookup("int8").orElseThrow();
        assertEquals((byte) 127, int8.read(new TokenValue("127", TokenForm.UNQUOTED)));
        org.junit.jupiter.api.Assertions.assertThrows(AtomValidationException.class,
                () -> int8.read(new TokenValue("128", TokenForm.UNQUOTED)));
    }
}
