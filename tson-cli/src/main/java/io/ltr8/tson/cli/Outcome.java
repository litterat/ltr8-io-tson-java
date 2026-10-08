package io.ltr8.tson.cli;

import io.ltr8.tson.base.Diagnostic;

import java.util.List;

/**
 * Whether a document will be accepted -- the question a sender checking a file or a request against a
 * deployment's schema and policy is asking.
 *
 * <p><b>Acceptance, not validity, is the headline.</b> A document is rejected by an invalidity every processor
 * repeats, and equally by a refusal under this deployment's own configuration -- its [TSON-DATA] §8.2 policy,
 * its §9.1 limits, or a schema it does not hold and will not fetch ([TSON-SCHEMA] §11.2): either way it will
 * not be accepted here, and the sender holds the fix. Which of the two it was -- the portable finding or the
 * local one -- is each diagnostic's {@link Diagnostic.Code}, where {@link Diagnostic.Code#verdict()} and {@link
 * Diagnostic.Code#isRefusal()} tell them apart. What leaves a document {@link #UNDETERMINED} is nothing anyone
 * present could judge it by: a schema that could not be obtained, a gap in this library, a type with no class
 * here (SPEC-FEEDBACK.md #1).
 *
 * <p><b>An enum rather than a {@code valid} boolean</b>, since {@code if (!valid)} reads "undetermined" as
 * "rejected" -- the assertion an agent acts on. There is no falsy shortcut past a three-member enum.
 */
public enum Outcome {

    /** Judged, and nothing was reported. */
    ACCEPTED,

    /** Rejected: the document is invalid, or this processor refused it under its policy or limits. */
    REJECTED,

    /** Not rejected, but something could not be judged, so acceptance cannot be stated. */
    UNDETERMINED;

    /**
     * The outcome a list of problems denotes: nothing reported is {@link #ACCEPTED}, any invalidity or refusal
     * is {@link #REJECTED}, and what is left is {@link #UNDETERMINED}.
     *
     * <p><b>One rejection is enough</b>, beside anything else reported: what went unjudged cannot make a
     * rejected document acceptable, only an unrejected one unknown. Which codes reject is {@link
     * Diagnostic.Code}'s to say, so this keeps no copy of the set.
     */
    static Outcome of(List<CliDiagnostic> errors) {
        if (errors.isEmpty()) {
            return ACCEPTED;
        }
        return errors.stream().anyMatch(error -> error.code().verdict() || error.code().isRefusal())
                ? REJECTED : UNDETERMINED;
    }

    /** The outcome of a whole run, on the same terms: any file rejected, else any undetermined, else accepted. */
    static Outcome ofFiles(List<FileReport> files) {
        if (files.stream().anyMatch(file -> file.outcome() == REJECTED)) {
            return REJECTED;
        }
        return files.stream().allMatch(file -> file.outcome() == ACCEPTED) ? ACCEPTED : UNDETERMINED;
    }
}
