package io.ltr8.tson.base.diagnostics;

import io.ltr8.tson.base.Diagnostic;

/**
 * The four components of a {@link Diagnostic} a <b>rule</b> determines: which rule fired, what it says, the
 * constraint that was not met, and what the document held instead. The other five are the read's -- its RFC
 * 6901 path, its schema identity and pointer, and both positions -- and no rule knows them.
 *
 * <p>That split is why this exists as a value rather than as a {@code Diagnostic} factory: a rule can be
 * stated once, in one place, for every encoding, while each read supplies where it happened.
 * {@code tson-atom}'s {@code AtomRefusal} is the same shape reached from the other direction -- it
 * classifies a family's own exception -- and the two agreeing is not a coincidence.
 *
 * @param code     which rule fired, from [TSON-DATA] §8.1's closed vocabulary. What a consumer routes on.
 * @param message  the rule in prose, in the <b>schema's</b> vernacular -- see {@link RecordDiagnostics}.
 * @param expected the constraint that was not met, machine-readable. The schema's, so it agrees everywhere.
 * @param actual   what the document held. The <b>encoding's</b>, because it echoes what was literally there:
 *                 {@code _} where a TSON document wrote one and {@code null} where a JSON document did.
 */
public record Refusal(Diagnostic.Code code, String message, String expected, String actual) {
}
