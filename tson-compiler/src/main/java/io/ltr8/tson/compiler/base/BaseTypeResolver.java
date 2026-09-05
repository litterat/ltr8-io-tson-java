package io.ltr8.tson.compiler.base;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;

/**
 * Base type resolution (§4): resolves a {@link TokenValue} to a {@link BaseValue} per the fixed
 * order of §4.5 -- boolean, number, string.
 *
 * <p>Quoted tokens always resolve to string regardless of content (§4.4: "Any quoted token
 * resolves to a string value") -- {@code "42"} and {@code "true"} are the strings {@code 42} and
 * {@code true}, not the number/boolean they'd be if unquoted. Only {@link TokenForm#UNQUOTED}
 * tokens attempt the boolean/number checks.
 *
 * <p><b>There is no {@code null}.</b> Absence has one spelling, {@code _} (§2.9), and it is lexical: it
 * arrives as its own token type and never as a {@link TokenValue}, so no order here can reach it. The
 * unquoted token {@code null} is the string {@code null}, as {@code frobnicate} is -- §7.7 rule 3 holds
 * without qualification, there being no word to except.
 *
 * <p>This applies only when no declared type information is in scope and the token carries no
 * built-in type annotation (§4's own applicability clause) -- callers must not invoke this on a
 * token annotated with a built-in-vocabulary type (§5, not yet implemented) or governed by a
 * schema ([TSON-SCHEMA]); this class has no way to detect either from a bare {@code TokenValue}
 * alone.
 */
public final class BaseTypeResolver {

    private BaseTypeResolver() {
    }

    public static BaseValue resolve(TokenValue token) {
        if (token.form() != TokenForm.UNQUOTED) {
            return new BaseValue.StringValue(token.text());
        }

        String text = token.text();
        if (text.equals("true")) {
            return new BaseValue.BooleanValue(true);
        }
        if (text.equals("false")) {
            return new BaseValue.BooleanValue(false);
        }

        return NumberGrammar.tryParse(text)
                .<BaseValue>map(BaseValue.NumberValue::new)
                .orElseGet(() -> new BaseValue.StringValue(text));
    }
}
