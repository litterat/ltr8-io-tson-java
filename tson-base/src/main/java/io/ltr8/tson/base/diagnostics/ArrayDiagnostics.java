package io.ltr8.tson.base.diagnostics;

import io.ltr8.tson.base.Diagnostic;

import java.math.BigInteger;

/**
 * What an array's or a set's rules say when a document breaks one -- stated once, for every encoding. See
 * {@link RecordDiagnostics} for why one place, and why the prose is the schema's vernacular rather than any
 * format's.
 *
 * <p><b>One class for both, because a set is an array that refuses a repeat.</b> [TSON-SCHEMA] §7.5 gives a
 * set the same element type, the same size facets and the same element state; what it adds is uniqueness over
 * the element type's value space. So the only rule here a plain array never reaches is {@link #repeatedElement}.
 *
 * @param typeName the array or set type being read, as the author wrote it
 */
public record ArrayDiagnostics(String typeName) {

    /** The value is not an array at all -- {@code found} is the encoding's description of what was there. */
    public Refusal notAnArray(String found) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "expected an array for '%s', found %s".formatted(typeName, found), "an array", found);
    }

    /**
     * Absence at an element position the schema does not admit one at ([TSON-SCHEMA] §7.6). {@code spelling}
     * is how the document said it, and appears in {@code actual} rather than in the prose -- the rule is about
     * the element's state, not about how absence was spelled.
     */
    public Refusal absentElement(int index, String spelling) {
        return new Refusal(Diagnostic.Code.FIELD_REQUIRED,
                "'%s' element [%d] is absent, but elements are required".formatted(typeName, index),
                "a value", spelling);
    }

    /**
     * [TSON-SCHEMA] §7.5: a set-typed position asserts uniqueness, so a repeat is an error at the repeated
     * occurrence rather than a member silently dropped -- "silently dropping a member the author wrote would
     * decode the document to something other than what it says".
     */
    public Refusal repeatedElement(String rendered) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "'%s' requires unique elements, '%s' appears more than once".formatted(typeName, rendered),
                "each element once", rendered);
    }

    /**
     * §5.3's {@code min_items}, counted over slots -- an absent element occupies one.
     *
     * <p>{@code expected} is the constraint in the form the atom vocabulary already states bounds in
     * ({@code >= 2}), rather than prose, because it is the machine-readable half and a consumer reading
     * {@code expected} across families should not meet two conventions.
     */
    public Refusal tooFewElements(BigInteger min, int size) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "'%s' has %d elements, fewer than the minimum %s".formatted(typeName, size, min),
                ">= " + min, String.valueOf(size));
    }

    /** §5.3's {@code max_items}, on the same terms. */
    public Refusal tooManyElements(BigInteger max, int size) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "'%s' has %d elements, more than the maximum %s".formatted(typeName, size, max),
                "<= " + max, String.valueOf(size));
    }
}
