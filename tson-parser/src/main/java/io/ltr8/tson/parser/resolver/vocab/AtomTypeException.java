package io.ltr8.tson.parser.resolver.vocab;

/**
 * A built-in vocabulary atom type (§5) rejected a token, either at {@link AtomParseException parse}
 * or {@link AtomValidationException validation} time -- §5.2's own distinction: "A token the atom's
 * grammar rejects 'is a parse error'; a parsed value violating the atom's range 'is a validation
 * error'." Sealed to those two so callers wanting to report the two categories differently (§8.1)
 * can {@code switch} exhaustively instead of string-sniffing a message.
 */
public sealed abstract class AtomTypeException extends RuntimeException
        permits AtomParseException, AtomValidationException {

    private static final long serialVersionUID = 1L;

    protected AtomTypeException(String message) {
        super(message);
    }
}
