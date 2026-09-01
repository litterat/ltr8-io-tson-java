package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;

import java.util.List;

/**
 * The result of one {@code validate} invocation: {@code valid} for the run as a whole, one {@link
 * FileReport} per data document validated, and {@code errors} for anything that went wrong with the
 * invocation itself rather than with any one document.
 *
 * <p><b>Always the shape {@code validate} emits</b>, one data file or twenty, so a machine consumer
 * never branches on file count to find the diagnostics. {@code compile} keeps rendering a bare {@link
 * ValidationReport} instead -- it checks exactly one schema and has no file list to name.
 *
 * <p><b>The two error lists match the two exit codes.</b> {@code errors} is populated only by the
 * failures that stop the run before any data is read -- a file that can't be read while being
 * classified, a schema document with no {@code !!id}, an argument list with no data files in it -- and
 * those are exit 2. A document that read but didn't validate lands in its own {@link FileReport}, and
 * that is exit 1. So {@code errors} non-empty means "the invocation was wrong", never "your document
 * was", and a consumer can tell the two apart without reading the messages. Shape matches {@code
 * diagnostics.tn}'s own {@code validation_run} field for field.
 *
 * <p><b>{@code policy} is stated once here rather than on the diagnostics.</b> A [TSON-DATA] §8.2
 * name-hygiene refusal depends on this processor's own configuration and on Unicode tables the UCD does
 * not freeze, so the same document may be refused here and accepted elsewhere; the run says what judged it,
 * one statement for every file in it, since the answer cannot differ between two problems in one
 * invocation. Present on a run that refused nothing too -- what it explains is the verdict, not the
 * refusal, and a consumer diffing two runs' reports needs it on the passing one as well.
 *
 * <p>Public for the same reason {@link CliDiagnostic} is -- see its own Javadoc.
 */
public record ValidationRun(boolean valid, CliPolicy policy, List<FileReport> files,
                            List<CliDiagnostic> errors) {

    /** A run that got as far as validating documents: the verdict is every file's verdict. */
    static ValidationRun of(CliPolicy policy, List<FileReport> files) {
        return new ValidationRun(files.stream().allMatch(FileReport::valid), policy, files, List.of());
    }

    /** A run that never reached a document -- no files, one run-level problem, and exit 2. */
    static ValidationRun failed(CliPolicy policy, Diagnostic.Code code, String message) {
        return new ValidationRun(false, policy, List.of(), List.of(CliDiagnostic.minimal(code, message)));
    }
}
