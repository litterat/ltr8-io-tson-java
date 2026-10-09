package io.ltr8.tson.atom;

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
    })
    void fullIntegerFamilyIsRegistered(String name) {
        assertTrue(BuiltinTypeVocabulary.lookup(name).isPresent());
    }

    /** A sign bound is a refinement written where it is wanted ({@code !integer ^ { min: 1 }}), not a name. */
    @ParameterizedTest
    @ValueSource(strings = {"positive_integer", "non_negative_integer", "negative_integer", "non_positive_integer"})
    void signBoundsAreNotBuiltInNames(String name) {
        assertTrue(BuiltinTypeVocabulary.lookup(name).isEmpty());
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
    @ValueSource(strings = {"bytes"})
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

        assertEquals(content, text.read(content));
    }

    /** Form is not meaning (§2.4): the same content quoted or not is the same string. */
    @org.junit.jupiter.api.Test
    void textReadsAQuotedNumericAsTheString() {
        AtomType<?> text = BuiltinTypeVocabulary.lookup("text").orElseThrow();

        assertEquals("42", text.read("42"));
        assertEquals("42", text.read("42"));
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
        assertEquals((byte) 127, int8.read("127"));
        org.junit.jupiter.api.Assertions.assertThrows(AtomValidationException.class,
                () -> int8.read("128"));
    }

    /** net.tn's {@code hostname}: RFC 1123 labels, read folded to lowercase. */
    @ParameterizedTest
    @ValueSource(strings = {"localhost", "example.com", "a.b-c.example", "xn--bcher-kva.example", "1host.example"})
    void hostnameAdmitsRfc1123Names(String name) {
        assertEquals(name, hostname().read(name));
    }

    @org.junit.jupiter.api.Test
    void hostnameIsReadFolded() {
        assertEquals("example.com", hostname().read("Example.COM"));
    }

    /**
     * A leading or trailing hyphen, an empty label, a trailing dot, a label past 63, a dotted-quad, a
     * non-letter-digit-hyphen character, and a fullwidth spelling that only a Unicode fold would bring to ASCII.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "-example.com", "example-.com", "a..b", "example.com.", "192.0.2.1", "under_score.example",
            "\uFF45xample.com",
    })
    void hostnameRefusesWhatRfc1123Does(String name) {
        org.junit.jupiter.api.Assertions.assertThrows(AtomValidationException.class, () -> hostname().read(name));
    }

    @org.junit.jupiter.api.Test
    void hostnameLabelsAndTotalAreBounded() {
        String label63 = "a".repeat(63);
        assertEquals(label63 + ".example", hostname().read(label63 + ".example"));
        org.junit.jupiter.api.Assertions.assertThrows(AtomValidationException.class,
                () -> hostname().read("a".repeat(64) + ".example"));
        String name253 = String.join(".", label63, label63, label63, "a".repeat(61));
        assertEquals(253, name253.length());
        assertEquals(name253, hostname().read(name253));
        org.junit.jupiter.api.Assertions.assertThrows(AtomValidationException.class,
                () -> hostname().read(name253 + "a"));
    }

    @SuppressWarnings("unchecked")
    private static AtomType<String> hostname() {
        return (AtomType<String>) BuiltinTypeVocabulary.lookup("hostname").orElseThrow();
    }
}
