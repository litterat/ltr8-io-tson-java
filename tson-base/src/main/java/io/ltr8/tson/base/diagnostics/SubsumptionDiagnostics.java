package io.ltr8.tson.base.diagnostics;

import io.ltr8.tson.base.Diagnostic;

/**
 * [TSON-SCHEMA] §7.2's rule that a value's own type annotation must be admitted by the position it stands in
 * -- stated once, for every encoding.
 *
 * <p><b>Not a record's rule</b>, which is why it sits beside {@link RecordDiagnostics} rather than inside it:
 * the rule governs every atom and product position, so an array, a map and a tuple refuse a wrong annotation
 * on these same terms. What the refusal never mentions is how the annotation was carried -- {@code !employee}
 * in TSON text, a {@code $type} member in JSON -- because that is the encoding's spelling and rides in
 * {@link Refusal#actual}.
 *
 * <p><b>The code is {@code TYPE_MISMATCH} and not {@code UNKNOWN_TYPE_REF}.</b> The written name resolves; it
 * names a type the position does not admit, which is a mismatch and not an unresolved reference. That keeps
 * the refusal in §8.1's {@code validation} category, where a read-time verdict against a schema that loaded
 * belongs, and leaves {@code UNKNOWN_TYPE_REF} to mean what it says -- a name denoting nothing, which is the
 * schemaless reader's own check and a genuinely unresolved reference.
 *
 * @param typeName the position's declared type, as the author wrote it
 */
public record SubsumptionDiagnostics(String typeName) {

    /**
     * The annotation names something the position does not admit, where the position's type has subtypes.
     *
     * @param written    the type name the document carried
     * @param admissible every name that would be admitted, joined {@code "a | b | c"}
     */
    public Refusal notAdmissible(String written, String admissible) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                ("'%s' is not admissible at a '%s' position -- a type annotation may name '%s' or one of its "
                        + "subtypes (%s), and this names neither (§7.2)")
                        .formatted(written, typeName, typeName, admissible),
                admissible, written);
    }

    /**
     * The same, where the position's type has no subtypes at all: the only admissible annotation is its own
     * name, so the remedy is to drop the annotation rather than to correct it.
     */
    public Refusal noSubtypeToName(String written) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                ("'%s' is not admissible at a '%s' position -- a type annotation must name the position's own "
                        + "type, which has no subtypes (§7.2)").formatted(written, typeName),
                typeName, written);
    }
}
