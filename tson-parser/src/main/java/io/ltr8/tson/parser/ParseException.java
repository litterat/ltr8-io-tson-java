package io.ltr8.tson.parser;

import io.ltr8.tson.parser.lexer.Position;

/**
 * A parser error (§8.1): structural mismatches -- unclosed brackets, adjacency violations,
 * unexpected tokens, missing separators, {@code !!} without an adjacent colon form, or a
 * directive name outside the closed positional set or outside its placement (§3.3).
 */
public final class ParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Position position;

    public ParseException(String message, Position position) {
        super(message + " at line " + position.line() + ", column " + position.column());
        this.position = position;
    }

    public Position position() {
        return position;
    }
}
