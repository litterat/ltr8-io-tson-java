package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.EmailType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code email} (core.tn, pinned to RFC 5322): the {@code dot-atom "@" dot-atom} core, plus the length and
 * pattern facets it composes from {@code text_type}. The deliberately-rejected-though-valid RFC 5322 forms
 * are pinned here too, so the subset is a tested decision rather than an accident -- see {@link EmailParser}.
 */
class EmailParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.SINGLE_LINE_QUOTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ada@example.com",
            "ada.lovelace@example.co.uk",
            "user+tag@example.com",              // '+' is atext
            "a!#$%&'*/=?^_`{|}~-@example.com",   // the rest of atext
            "x@y",                               // no dot required on either side
            "UPPER@EXAMPLE.COM"})
    void acceptsTheDotAtomForm(String text) {
        assertEquals(text, EmailParser.UNCONSTRAINED.read(token(text)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ada",                    // no '@'
            "ada@",                   // no domain
            "@example.com",           // no local part
            "ada@@example.com",
            ".ada@example.com",       // leading dot
            "ada.@example.com",       // trailing dot
            "ada..lovelace@example.com",   // doubled dot
            "ada@example..com",
            "ada@.example.com",
            "ada lovelace@example.com",    // space
            ""})
    void rejectsMalformedAddresses(String text) {
        assertThrows(AtomParseException.class, () -> EmailParser.UNCONSTRAINED.read(token(text)));
    }

    /**
     * Legal RFC 5322, deliberately rejected. Accepting these would admit spaces, brackets and parentheses
     * into a field most consumers treat as a simple token; the trade is stated on {@link EmailParser}.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "\"ada lovelace\"@example.com",   // quoted-string local part
            "ada@[192.0.2.1]",                // domain literal
            "ada(the countess)@example.com"}) // comment
    void rejectsTheRfc5322FormsThisSubsetLeavesOut(String text) {
        assertThrows(AtomParseException.class, () -> EmailParser.UNCONSTRAINED.read(token(text)));
    }

    // ── The text_type facets email_type composes ─────────────────────────

    private static EmailParser withMaxLength(int max) {
        return new EmailParser(new EmailType(EmailType.UNCONSTRAINED.spec(), Optional.empty(),
                Optional.of(max), Optional.empty(), Optional.empty()));
    }

    @Test
    void appliesTheLengthFacetsItComposesFromTextType() {
        assertEquals("a@b.co", withMaxLength(6).read(token("a@b.co")));
        assertThrows(AtomValidationException.class, () -> withMaxLength(6).read(token("ada@example.com")));
    }

    /** A facet violation is a validation error; a malformed address is a parse error (§5.2's own split). */
    @Test
    void aFacetViolationAndAMalformedAddressAreDifferentFailures() {
        assertThrows(AtomValidationException.class, () -> withMaxLength(6).read(token("ada@example.com")));
        assertThrows(AtomParseException.class, () -> withMaxLength(6).read(token("nope")));
    }

    @Test
    void appliesThePatternFacet() {
        EmailParser corporate = new EmailParser(new EmailType(EmailType.UNCONSTRAINED.spec(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(".*@example\\.com")));

        assertEquals("ada@example.com", corporate.read(token("ada@example.com")));
        assertThrows(AtomValidationException.class, () -> corporate.read(token("ada@other.com")));
    }
}
