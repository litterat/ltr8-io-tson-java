package io.ltr8.tson.compiler.atom;

/**
 * A built-in vocabulary atom type (§5) rejected a token, either at {@link AtomParseException parse}
 * or {@link AtomValidationException validation} time -- §5.2's own distinction: "A token the atom's
 * grammar rejects 'is a parse error'; a parsed value violating the atom's range 'is a validation
 * error'." Sealed to those two so callers wanting to report the two categories differently (§8.1)
 * can {@code switch} exhaustively instead of string-sniffing a message.
 *
 * <p><b>{@link #expected()} is the machine-readable half, and is the point of this type carrying two
 * strings rather than one.</b> {@code getMessage()} is a whole sentence about this rejection; {@code
 * expected()} is the *constraint that was violated*, standing alone, and lands verbatim in a {@link
 * io.ltr8.tson.compiler.Diagnostic}'s own {@code expected}. A consumer that would otherwise have to
 * recover the bound by regexing the sentence reads it directly:
 *
 * <pre>{@code
 * message   '99999' is greater than the maximum 100
 * expected  <= 100
 * actual    99999
 * }</pre>
 *
 * <p>The vocabulary is deliberately narrow, and every throw site in this package draws from it:
 *
 * <ul>
 *   <li><b>an ordering bound</b> -- {@code >= 1}, {@code > 1}, {@code <= 100}, {@code < 100}, and a
 *   two-sided range as {@code >= -128 and <= 127}. Operator form, not prose, because the bound is
 *   the whole content and a comparison is how a repair loop will act on it.</li>
 *   <li><b>a membership</b> -- {@code one of (PENDING, SHIPPED, DELIVERED)}, matching the same
 *   spelling {@code RecordAbstractReader} already uses for a field group and a type's field list.</li>
 *   <li><b>a length</b> -- {@code exactly 4 characters}, {@code at least 2 characters}, {@code at
 *   most 10 characters}; a binary atom says {@code bytes}.</li>
 *   <li><b>a pattern</b> -- {@code matching <i-regexp>}, the pattern itself unquoted and unescaped,
 *   since it is already the schema's own text.</li>
 *   <li><b>a grammar</b>, for a parse failure -- the production the token had to satisfy, named the
 *   way the spec names it ({@code an RFC 3339 date-time}, {@code an integer or based-integer},
 *   {@code a base64 encoding}). This is the one case where {@code expected} names a shape rather
 *   than a facet, because a shape is exactly what was expected.</li>
 *   <li><b>a prohibition</b> -- {@code not NaN}, {@code a finite value}, for the {@code float} allow
 *   flags, whose facet is a boolean and whose violated constraint is therefore a negation.</li>
 * </ul>
 *
 * <p>No site invents a phrase outside those six shapes. Each is a fragment, not a sentence: a
 * renderer composes ("expected {@code <= 100}, found {@code 99999}"), and nothing reads correctly
 * only when glued to a particular prefix.
 */
public sealed abstract class AtomTypeException extends RuntimeException
        permits AtomParseException, AtomValidationException {

    private static final long serialVersionUID = 1L;

    private final String expected;

    protected AtomTypeException(String message, String expected) {
        super(message);
        this.expected = expected;
    }

    /** The violated constraint, standing alone -- see this class's own Javadoc for the vocabulary. */
    public String expected() {
        return expected;
    }
}
