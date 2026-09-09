package io.ltr8.tson.atom;

import io.ltr8.tson.base.Diagnostic;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one place an atom's refusal becomes a diagnostic code.
 *
 * <p>What is asserted is [TSON-DATA] §8.1's split, over the same token faced by the same family: a token the
 * grammar rejects is a <b>resolver</b> error and a parsed value outside the family's range is a
 * <b>validation</b> error. Both used to carry {@code ATOM_CONSTRAINT_VIOLATION}, which put a resolver error
 * in the validation category — the mapping the conformance corpus asserts against.
 */
class AtomRefusalTest {

    /** {@code int8}, so both refusals are reachable from one family with one target. */
    private static final AtomType<?> INT8 = BuiltinTypeVocabulary.lookup("int8").orElseThrow();

    private static AtomRefusal refusing(String text) {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> INT8
                .boundTo(byte.class).orElseThrow().read(text));
        return AtomRefusal.of(failure, text, byte.class);
    }

    @Test
    void a_token_the_grammar_rejects_is_a_resolver_error() {
        // §8.1: "the structural parser has already accepted the document before an atom contract is
        // consulted, so contract failures resolve, they do not parse."
        AtomRefusal refusal = refusing("twelve");

        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, refusal.code());
        assertEquals("an integer or based-integer form", refusal.expected());
        assertEquals("twelve", refusal.actual());
    }

    @Test
    void a_value_outside_the_range_is_a_validation_error() {
        AtomRefusal refusal = refusing("200");

        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, refusal.code());
        assertEquals(">= -128 and <= 127", refusal.expected());
    }

    /**
     * The pair that motivated the split: one family, one target, two tokens, and nothing but the code to
     * tell a consumer which category §8.1 puts each in — the messages are equally "the atom said no".
     */
    @Test
    void the_two_are_told_apart_by_the_code_and_by_nothing_else() {
        assertTrue(refusing("twelve").code() != refusing("200").code());
        assertTrue(refusing("twelve").code().verdict(), "both are verdicts on the document");
        assertTrue(refusing("200").code().verdict(), "both are verdicts on the document");
    }

    @Test
    void a_target_the_family_does_not_reach_is_settled_before_any_value() {
        // `number` reads an approximate value and no integral target can hold one -- a fact about the two
        // types rather than about a token, so it is answered where the reader is built and there is no
        // refusal to classify. This is what closes the numeric route into TYPE_MISMATCH: the
        // IllegalArgumentException that fed it was NumberNarrowing being handed a target it does not reach,
        // which boundTo now refuses first.
        AtomType<?> number = BuiltinTypeVocabulary.lookup("number").orElseThrow();
        assertTrue(number.boundTo(int.class).isEmpty());

        // What a numeric family still refuses at the read is a value outside the target's range, which is a
        // verdict on the token and files as one.
        AtomType<?> int32 = BuiltinTypeVocabulary.lookup("int32").orElseThrow();
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> int32.boundTo(byte.class).orElseThrow().read("300"));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, AtomRefusal.of(failure, "300", byte.class).code());
    }

    @Test
    void a_value_the_family_admits_and_the_target_cannot_hold_is_a_constraint() {
        // `integer` is unbounded, so the range check passes and the narrowing is what fails.
        AtomType<?> integer = HostAtoms.forNumberContentHostType(BigInteger.class).orElseThrow();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> integer
                .boundTo(byte.class).orElseThrow().read("200"));

        AtomRefusal refusal = AtomRefusal.of(failure, "200", byte.class);
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, refusal.code());
        assertEquals("a value that fits byte", refusal.expected());
    }

    /** A refusal this does not recognise is a fault, and reporting one as a verdict would hide a bug. */
    @Test
    void anything_else_is_rethrown() {
        RuntimeException fault = new IllegalStateException("an invariant broke");

        assertSame(fault, assertThrows(IllegalStateException.class,
                () -> AtomRefusal.of(fault, "x", String.class)));
    }

    /** The entry name leads the sentence and stays out of the constraint, which is the atom's alone. */
    @Test
    void naming_the_entry_touches_the_message_and_not_the_expectation() {
        AtomRefusal named = refusing("200").named("my_percentage");

        assertTrue(named.message().startsWith("'my_percentage': "), named.message());
        assertEquals(">= -128 and <= 127", named.expected());
        assertEquals(refusing("200").code(), named.code());
    }
}
