package io.ltr8.tson.parser.resolver;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseTypeResolverTest {

    private static BaseValue resolve(String text, TokenForm form) {
        return BaseTypeResolver.resolve(new TokenValue(text, form));
    }

    private static BaseValue resolveUnquoted(String text) {
        return resolve(text, TokenForm.UNQUOTED);
    }

    // ── null / boolean ───────────────────────────────────────────────────

    @Test
    void unquotedNullResolvesToNull() {
        assertInstanceOf(BaseValue.NullValue.class, resolveUnquoted("null"));
    }

    @Test
    void unquotedTrueAndFalseResolveToBoolean() {
        assertTrue(((BaseValue.BooleanValue) resolveUnquoted("true")).value());
        assertFalse(((BaseValue.BooleanValue) resolveUnquoted("false")).value());
    }

    @Test
    void nullAndBooleanAreCaseSensitive() {
        // Spec §4.1/§4.2: "case-sensitive, lowercase only". No, Yes, on, off, True, FALSE etc. are not recognised.
        assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("Null"));
        assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("NULL"));
        assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("True"));
        assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("FALSE"));
        assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("yes"));
        assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("on"));
    }

    // ── number delegation ────────────────────────────────────────────────

    @Test
    void unquotedNumberResolvesToNumberValue() {
        BaseValue.NumberValue v = assertInstanceOf(BaseValue.NumberValue.class, resolveUnquoted("1042"));
        assertInstanceOf(NumberForm.IntegerForm.class, v.form());
    }

    @Test
    void basedIntegerResolvesToNumberValue() {
        BaseValue.NumberValue v = assertInstanceOf(BaseValue.NumberValue.class, resolveUnquoted("0b0110"));
        assertEquals(NumberForm.BasedIntegerForm.Radix.BINARY, ((NumberForm.BasedIntegerForm) v.form()).radix());
    }

    // ── string fallback ──────────────────────────────────────────────────

    @Test
    void nearMissNumericFormsResolveToString() {
        // Spec §4.4's own examples: leading zeros and a second dot fail the number grammar.
        assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("007"));
        assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("1.2.3"));
    }

    @Test
    void plainWordsResolveToString() {
        BaseValue.StringValue v = assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("GOLD"));
        assertEquals("GOLD", v.text());
        assertInstanceOf(BaseValue.StringValue.class, resolveUnquoted("A-100"));
    }

    // ── quoted tokens always resolve to string, regardless of content ────

    @Test
    void quotedTokenIsAlwaysString() {
        assertInstanceOf(BaseValue.StringValue.class, resolve("42", TokenForm.SINGLE_LINE_QUOTED));
        assertInstanceOf(BaseValue.StringValue.class, resolve("true", TokenForm.SINGLE_LINE_QUOTED));
        assertInstanceOf(BaseValue.StringValue.class, resolve("null", TokenForm.SINGLE_LINE_QUOTED));
    }

    @Test
    void quotedStringPreservesExactText() {
        BaseValue.StringValue v = assertInstanceOf(BaseValue.StringValue.class,
                resolve("42", TokenForm.SINGLE_LINE_QUOTED));
        assertEquals("42", v.text());
    }

    @Test
    void multilineQuotedTokenIsAlwaysString() {
        assertInstanceOf(BaseValue.StringValue.class, resolve("null", TokenForm.MULTI_LINE_QUOTED));
    }

    @Test
    void quotedAndUnquotedSameTextResolveDifferently() {
        // "42" (number) vs "42" (string) -- form is consulted exactly once, at resolution (§2.4).
        assertInstanceOf(BaseValue.NumberValue.class, resolveUnquoted("42"));
        assertInstanceOf(BaseValue.StringValue.class, resolve("42", TokenForm.SINGLE_LINE_QUOTED));
    }
}
