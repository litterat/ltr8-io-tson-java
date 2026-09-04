package io.ltr8.tson;

import io.ltr8.tson.schema.TsonSchemaValidationException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code pattern} whose language is empty admits no string, so the type it constrains is uninhabited and no
 * document can satisfy it — a schema-load error, on the same terms as {@code { min: 10  max: 3 }}.
 *
 * <p><b>It is reachable, which is the reason to run it.</b> RFC 9485 removes the constructs that usually
 * write an empty language — no lookaround, no anchors, no character-class subtraction — but a negated class
 * covering the whole code-point space survives: {@code [^\\p{L}\\P{L}]} is every letter and every non-letter,
 * negated.
 *
 * <p>The check lives in {@code tson-compiler} rather than on the family, because deciding a pattern's
 * language needs a regex engine and {@code tson-schema} depends on nothing. The test is the engine's own
 * disjointness asked of the pattern against itself, which is exact.
 */
class EmptyPatternTest {

    private static void resolve(String declarations) {
        Tson.builder().build().resolve("""
                !!id:"https://example.test/empty-pattern.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { %s }
                """.formatted(declarations));
    }

    /** The case: a negated class covering everything, which parses and matches nothing. */
    @Test
    void aPatternMatchingNothingIsRefusedAtSchemaLoad() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolve("""
                        impossible => !text ^ { pattern: "[^\\\\p{L}\\\\P{L}]" }"""));

        assertTrue(thrown.getMessage().contains("matches no string at all"), thrown.getMessage());
    }

    /** An ordinary pattern is untouched, including one that matches only the empty string. */
    @Test
    void aPatternThatMatchesSomethingIsAccepted() {
        assertDoesNotThrow(() -> resolve("""
                code => !text ^ { pattern: "[A-Z]{3}" }"""));
        assertDoesNotThrow(() -> resolve("""
                nothing_much => !text ^ { pattern: "a{0}" }"""));
    }

    /**
     * A pattern the engine cannot parse raises nothing here — the atom's own reader parser rejects it under
     * its own message, and reporting the same defect from two layers helps nobody.
     */
    @Test
    void anUnparseablePatternIsLeftToTheAtomsOwnMessage() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolve("""
                        broken => !text ^ { pattern: "[a-" }"""));

        assertTrue(!thrown.getMessage().contains("matches no string at all"), thrown.getMessage());
    }
}
