package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;

import java.util.List;

/**
 * Whether a document was checked, and if it was, what checking found.
 *
 * <p><b>Two questions that a single {@code valid} boolean carried on one bit.</b> A document whose schema
 * could not be obtained, or whose types have no Java class here, was never read at all -- reporting it as
 * {@code valid: false} asserts a verdict the run cannot make, and it is the assertion an agent acts on when
 * it reads {@code if (!valid)}. The exit code has said this for as long as 69 has existed; the envelope
 * beside it said "rejected".
 *
 * <p><b>An enum rather than a second boolean</b>, for the reason {@link Diagnostic.Code} carries a fetch
 * failure rather than a field beside it: {@code checked = false, valid = true} would be representable and
 * meaningless, and a second boolean does not defeat the read that caused the problem -- {@code if (!valid)}
 * still says rejected. Making {@code valid} optional would not either, {@code null} being falsy in the
 * languages that consume this. There is no falsy shortcut past a three-member enum.
 */
public enum Outcome {

    /** Checked, and nothing was reported. */
    VALID,

    /** Checked and rejected -- including a [TSON-DATA] §8.2 refusal, where the sender still holds the fix. */
    INVALID,

    /** No verdict: something was reported that says the document could not be judged at all. */
    NOT_CHECKED;

    /**
     * The outcome a list of problems denotes: nothing reported is {@link #VALID}, anything that is not a
     * verdict makes the whole thing {@link #NOT_CHECKED}, and what is left is {@link #INVALID}.
     *
     * <p><b>One non-verdict is enough</b>, the same way one makes the run's exit code a non-verdict: the
     * ordinary problems beside it are real and still reported, but part of the document went unchecked, so
     * "invalid" is a claim about the whole that cannot be made. Which codes those are is {@link
     * Diagnostic.Code#verdict}'s to say, so this does not keep a second copy of the set.
     */
    static Outcome of(List<CliDiagnostic> errors) {
        if (errors.isEmpty()) {
            return VALID;
        }
        return errors.stream().allMatch(error -> error.code().verdict()) ? INVALID : NOT_CHECKED;
    }

    /** The outcome of a whole run: the least settled of its files', a run being no better than its parts. */
    static Outcome ofFiles(List<FileReport> files) {
        if (files.stream().anyMatch(file -> file.outcome() == NOT_CHECKED)) {
            return NOT_CHECKED;
        }
        return files.stream().allMatch(file -> file.outcome() == VALID) ? VALID : INVALID;
    }
}
