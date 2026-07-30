package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.compiler.ast.TokenForm;

/**
 * A leaf {@code token} core-value (§2.4, §7.4) -- the streaming counterpart to {@code
 * ast.TokenValue}, reusing its {@link TokenForm} directly rather than redeclaring it. {@code
 * text} is already escape-decoded and, for multi-line tokens, common-indentation-stripped;
 * unresolved and uninterpreted, exactly as {@code ast.TokenValue.text()} is.
 *
 * <p>Named {@code TokenEvent}, not {@code Token}, to stay unambiguous alongside {@code
 * io.ltr8.tson.compiler.lexer.Token} (the lexical token this is built from) wherever both are in
 * scope.
 */
public record TokenEvent(String text, TokenForm form, Position position) implements TsonEvent {
}
