package io.ltr8.tson.atom;

import io.ltr8.tson.base.Diagnostic;

/**
 * One atom's refusal of one token, shaped for a {@link Diagnostic} and classified in one place.
 *
 * <p><b>Why this is not a {@code Diagnostic} factory.</b> A diagnostic needs its location -- the data path,
 * the position, the schema pointer -- and only the reader holds those; a read context builds the diagnostic
 * around them. What is shared is everything else: the code, the sentence, and the constraint that failed.
 * So this carries the four non-locational components and each caller reports them where it stands.
 *
 * <p><b>Why it lives here.</b> {@link Diagnostic}'s ten {@code of*} factories each switch on an exception an
 * <em>encoding</em> declares, which is what keeps the base free of any one encoding's machinery.
 * {@link AtomTypeException} is the exception to that rule and the reason this class exists: it is the
 * vocabulary's own and neither encoding's, so a factory for it on {@code Diagnostic} would make the base
 * depend on the vocabulary, and a copy in each reader is how two encodings come to disagree about the same
 * refusal. They already had: one reported a target that cannot represent a family's value as a bind problem
 * and the other as a type mismatch, and nothing said which was right.
 *
 * <p><b>The classification is [TSON-DATA] §5.2's and §8.1's, not a preference.</b> §5.2 splits the refusal --
 * "a token the atom's grammar rejects is a parse error; a parsed value violating the atom's range is a
 * validation error" -- and §8.1 files the halves in different categories: a token a parsing contract rejects
 * is a <b>resolver</b> error, since "the structural parser has already accepted the document before an atom
 * contract is consulted, so contract failures resolve, they do not parse", where a range violation is a
 * <b>validation</b> error. {@code AtomTypeException} is sealed to exactly the two subtypes so this switch is
 * exhaustive rather than a guess.
 */
public record AtomRefusal(Diagnostic.Code code, String message, String expected, String actual) {

    /**
     * The refusal {@code failure} states about {@code text}, read at {@code target}.
     *
     * <p>Handles the three throwables an {@link AtomType#read(String)} raises, which are three
     * different facts and are deliberately not one code:
     *
     * <ul>
     *   <li>{@link AtomParseException} -- the token is not of the family's form. §8.1's resolver error.</li>
     *   <li>{@link AtomValidationException} -- the value is outside what the family admits. Validation.</li>
     *   <li>{@link ArithmeticException} from the numeric narrowing -- the family accepted the value and
     *       {@code target} cannot hold it exactly. Reachable only where the target is narrower than the
     *       atom guarantees, which is a document written against a wider type than the class declares.</li>
     *   <li>{@link IllegalArgumentException} from the same -- {@code target} cannot represent the family's
     *       values at all ({@code !number 1.5} at an {@code int} component), so the type-ref and the class
     *       name different things.</li>
     * </ul>
     *
     * <p>Anything else is rethrown: a refusal this does not recognise is a fault, and swallowing it into a
     * diagnostic would report a bug in this library as a verdict on someone's document.
     */
    public static AtomRefusal of(RuntimeException failure, String text, Class<?> target) {
        return switch (failure) {
            case AtomParseException e -> new AtomRefusal(Diagnostic.Code.ATOM_FORM_INVALID,
                    e.getMessage(), e.expected(), text);
            case AtomValidationException e -> new AtomRefusal(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                    e.getMessage(), e.expected(), text);
            case ArithmeticException ignored -> new AtomRefusal(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                    text + " does not fit in " + target.getSimpleName(),
                    "a value that fits " + target.getSimpleName(), text);
            case IllegalArgumentException e -> new AtomRefusal(Diagnostic.Code.TYPE_MISMATCH,
                    "cannot bind '" + text + "' to " + target.getSimpleName() + ": " + e.getMessage(),
                    target.getSimpleName(), text);
            default -> throw failure;
        };
    }

    /**
     * The same refusal with {@code prefix} leading the sentence -- for a reader naming the schema entry the
     * position belongs to, which the atom cannot know. {@code expected} is untouched: it is the constraint
     * that failed and nothing about which entry holds it belongs in it.
     */
    public AtomRefusal named(String prefix) {
        return new AtomRefusal(code, "'" + prefix + "': " + message, expected, actual);
    }
}
