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
 * <p><b>A distinct type rather than a half-built {@link Diagnostic}, and the difference is what the compiler
 * can catch.</b> A {@code Diagnostic} with its five location components empty is a valid-looking value that
 * nothing should ever hand to a {@link io.ltr8.tson.base.DiagnosticsReceiver}, and with nine components
 * nothing would notice if someone did. A {@code Refusal} cannot be reported at all: the only thing that
 * accepts one is a read context, which is the only thing that knows where it happened.
 *
 * <p><b>The unpacking lives on the read contexts, not in the readers</b> -- {@code report(Refusal)} on each --
 * so one place adds a location and one place takes this apart, however many families come to state rules.
 *
 * @param code     which rule fired, from [TSON-DATA] §8.1's closed vocabulary. What a consumer routes on.
 * @param message  the rule in prose, in the <b>schema's</b> vernacular -- see {@link RecordDiagnostics}.
 * @param expected the constraint that was not met, machine-readable. The schema's, so it agrees everywhere.
 * @param actual   what the document held. The <b>encoding's</b>, because it echoes what was literally there:
 *                 {@code _} where a TSON document wrote one and {@code null} where a JSON document did.
 */
public record Refusal(Diagnostic.Code code, String message, String expected, String actual) {
}
