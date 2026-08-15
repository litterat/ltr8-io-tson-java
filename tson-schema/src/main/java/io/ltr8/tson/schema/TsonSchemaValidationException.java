package io.ltr8.tson.schema;

/**
 * <b>The author's schema is wrong</b> -- a rule the spec states, broken by the document, at any phase that
 * reads one: resolution (a tightening outside §5.7's transition table, a refinement body that adds rather
 * than tightens, a name in a {@code !} position resolving in neither namespace), linking (an unresolved
 * reference, a constructor declared by a schema no meta-kernel governs), or registration (a
 * malformed/non-canonical {@code !!id}, a duplicate identity). Unchecked, matching {@code LexException}/
 * {@code TsonParseException}'s own established shape elsewhere in this codebase.
 *
 * <p><b>Not</b> a library gap -- that is {@code UnsupportedOperationException}, deliberately a different
 * type, because only this one is collected into a {@code Diagnostic} by the resolver's and linker's
 * reporting overloads: a misfiled author error aborts the whole run instead of joining the other problems,
 * and tells the author their correct reading of the spec is this library's fault. The test for which is
 * which: <b>a schema error's verdict does not change when this library improves; a gap's does.</b>
 */
public final class TsonSchemaValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TsonSchemaValidationException(String message) {
        super(message);
    }

    /**
     * For an error discovered by machinery that reports in its own currency and has to be re-stated as a
     * schema error -- notably a {@code TsonReadException} from binding a constructor body through the
     * governing meta's compiled reader, where the body is data of the constructor's vocabulary but a
     * failure to read it is the schema author's problem. {@code cause} is kept for the stack trace alone;
     * the message must already say everything a reader of the diagnostic needs.
     */
    public TsonSchemaValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
