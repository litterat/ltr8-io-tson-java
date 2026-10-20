package io.ltr8.tson.base.diagnostics;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.unicode.ConfusableNames;

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
 * <p><b>A type no author named has no name in the message.</b> {@code typeName} is {@code null} for a form with
 * no spelling of its own -- {@code set<text>} at a use site is its expansion, a set no author named -- and
 * each sentence then states the rule alone, the diagnostic's position saying where.
 *
 * @param typeName the array or set type being read, as the author wrote it, or {@code null} for none
 */
public record ArrayDiagnostics(String typeName) {

    /** The value is not an array at all -- {@code found} is the encoding's description of what was there. */
    public Refusal notAnArray(String found) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                (typeName == null ? "expected an array, found %s".formatted(found)
                        : "expected an array for '%s', found %s".formatted(typeName, found)), "an array", found);
    }

    /**
     * Absence at an element position the schema does not admit one at ([TSON-SCHEMA] §7.6). {@code spelling}
     * is how the document said it, and appears in {@code actual} rather than in the prose -- the rule is about
     * the element's state, not about how absence was spelled.
     */
    public Refusal voidElement(int index, String spelling) {
        return new Refusal(Diagnostic.Code.FIELD_REQUIRED,
                subject("element [%d] is absent, but elements are required".formatted(index)),
                "a value", spelling);
    }

    /**
     * [TSON-SCHEMA] §7.5: a set-typed position asserts uniqueness, so a repeat is an error at the repeated
     * occurrence rather than a member silently dropped -- "silently dropping a member the author wrote would
     * decode the document to something other than what it says".
     */
    public Refusal repeatedElement(String rendered) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                (typeName == null ? "elements must be unique, '%s' appears more than once".formatted(rendered)
                        : "'%s' requires unique elements, '%s' appears more than once".formatted(typeName, rendered)),
                "each element once", rendered);
    }

    /**
     * [TSON-DATA] §8.2's look-alike rule over the elements of an array whose elements are unique -- a set, or any
     * array marked {@code unique_items} -- and whose element type is an identifier family: one naming scope as an
     * identifier-keyed map's keys are ([TSON-SCHEMA] §11.4): a policy refusal, reported at
     * the second element.
     */
    public Refusal confusableElements(ConfusableNames.Collision collision) {
        return new Refusal(Diagnostic.Code.CONFUSABLE_NAMES,
                (typeName == null ? "elements read alike: %s".formatted(collision.describe())
                        : "'%s' has elements that read alike: %s".formatted(typeName, collision.describe())),
                "elements a reader can tell apart", "'" + collision.second() + "'");
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
                subject("%d elements, fewer than the minimum %s".formatted(size, min), "has "),
                ">= " + min, String.valueOf(size));
    }

    /** {@code rest} led by the type's name, or alone where it has none. */
    private String subject(String rest) {
        return subject(rest, "");
    }

    /** The same, with {@code verb} between a name and {@code rest}: {@code 'tags' has 3 elements, ...}. */
    private String subject(String rest, String verb) {
        return typeName == null ? rest : "'" + typeName + "' " + verb + rest;
    }

    /** §5.3's {@code max_items}, on the same terms. */
    public Refusal tooManyElements(BigInteger max, int size) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                subject("%d elements, more than the maximum %s".formatted(size, max), "has "),
                "<= " + max, String.valueOf(size));
    }
}
