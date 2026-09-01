package io.ltr8.tson.cli;

import io.ltr8.tson.Tson;

/**
 * {@code tson policy [--output text|json|tson]} -- prints the [TSON-DATA] §8.2 Unicode policy this build
 * applies, with no document in hand.
 *
 * <p><b>This is the surface that makes a refusal avoidable rather than merely explicable.</b> §8.2's three
 * name-hygiene rules read data the Unicode Consortium does not freeze, at a level the reading deployment
 * chooses, so a name one processor accepts another refuses. A sender that can read the policy before it
 * writes never writes the name that would be refused; one that learns it only from the refusal has already
 * spent a round trip, which is exactly the round trip TSON's one-shot aim exists to remove. The same record
 * rides on every {@link ValidationRun}, so a report and this command state one fact one way.
 *
 * <p>Takes the same policy flags {@code validate} and {@code compile} do, so it doubles as their dry run:
 * {@code tson policy --identifier-policy ascii-only} prints exactly what a validate under those flags would
 * apply.
 *
 * <p>Exit 0 always: this is a question about this processor, and it has an answer whatever the state of
 * anyone's documents.
 */
final class PolicyCommand {

    private PolicyCommand() {
    }

    static int run(OutputFormat format, PolicyOptions policies) {
        // Through a real Tson rather than from the flags directly: this prints what a read would actually be
        // judged under, which is the question, and the two would only ever agree by inspection otherwise.
        Tson tson = policies.applyTo(Tson.builder()).build();
        System.out.println(format.render(CliPolicy.from(tson.processorPolicy())));
        return 0;
    }
}
