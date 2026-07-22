package io.ltr8.tson.parser.resolver.vocab;

/**
 * A token's content doesn't match the atom's parsing contract (§5.2) -- e.g. {@code twelve} under
 * {@code !int32}, or a float/special-value token under an integer atom that only accepts
 * {@code integer}/{@code based-integer} forms (§5.6).
 */
public final class AtomParseException extends AtomTypeException {

    private static final long serialVersionUID = 1L;

    public AtomParseException(String message) {
        super(message);
    }
}
