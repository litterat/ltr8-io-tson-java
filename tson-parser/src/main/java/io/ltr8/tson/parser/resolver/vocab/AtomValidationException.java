package io.ltr8.tson.parser.resolver.vocab;

/**
 * A token parsed successfully under the atom's grammar but the resulting value violates one of the
 * atom's constraints (§5.2) -- e.g. {@code 9999999999} under {@code !int32} (parses as an integer,
 * fails the 32-bit range) or {@code -10} under {@code !uint32} (parses and is in-range for a signed
 * form, fails the unsigned range -- §5.6: "the range constraint, not the lexer, enforces
 * unsignedness").
 */
public final class AtomValidationException extends AtomTypeException {

    private static final long serialVersionUID = 1L;

    public AtomValidationException(String message) {
        super(message);
    }
}
