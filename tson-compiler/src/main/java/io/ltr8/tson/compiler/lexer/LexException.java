package io.ltr8.tson.compiler.lexer;

import io.ltr8.tson.compiler.Position;

/**
 * A lexer error (spec §8.1): a malformed token -- an unterminated quoted or multi-line token, an
 * invalid or unpaired-surrogate escape, an unrecognised character, or an unquoted token that is not
 * NFC-normalized.
 *
 * <p><b>{@link #getMessage()} states what went wrong, never where</b> -- {@link #position()} is the
 * location, and a {@code Diagnostic} built from this carries it structurally; see {@code
 * TsonParseException} for the full reasoning. {@link #toString()} appends it for a stack trace.
 */
public final class LexException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Position position;

    public LexException(String message, Position position) {
        super(message);
        this.position = position;
    }

    public Position position() {
        return position;
    }

    /** The message plus its location -- what a stack trace prints, and the only place the two are joined. */
    @Override
    public String toString() {
        return super.toString() + " at line " + position.line() + ", column " + position.column();
    }
}
