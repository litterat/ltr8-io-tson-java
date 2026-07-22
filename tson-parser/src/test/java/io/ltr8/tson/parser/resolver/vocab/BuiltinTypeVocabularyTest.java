package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinTypeVocabularyTest {

    // §5.6 as published: only these four widths. See SPEC-FEEDBACK.md #6 for the rest.
    @ParameterizedTest
    @ValueSource(strings = {"int32", "int64", "uint32", "uint64"})
    void publishedFixedWidthIntegersAreRegistered(String name) {
        assertTrue(BuiltinTypeVocabulary.lookup(name).isPresent());
    }

    // core.tn1's full width ladder, extended per SPEC-FEEDBACK.md #6 (confirmed oversight, not
    // deliberate scoping).
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
    @ValueSource(strings = {"rational", "complex", "uuid", "not_a_type"})
    void namesFromFamiliesNotYetImplementedAreNotRegistered(String name) {
        assertFalse(BuiltinTypeVocabulary.lookup(name).isPresent());
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
