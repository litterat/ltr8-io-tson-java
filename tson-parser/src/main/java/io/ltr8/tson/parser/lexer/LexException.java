package io.ltr8.tson.parser.lexer;

/**
 * A lexer error (spec §8.1): a malformed token — an unterminated quoted or
 * multi-line token, an invalid or unpaired-surrogate escape, an unrecognised
 * character, or an unquoted token that is not NFC-normalized.
 */
public final class LexException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Position position;

    public LexException(String message, Position position) {
        super(message + " at line " + position.line() + ", column " + position.column());
        this.position = position;
    }

    public Position position() {
        return position;
    }
}
