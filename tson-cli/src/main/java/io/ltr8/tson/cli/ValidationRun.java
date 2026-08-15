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
 * <p>Public for the same reason {@link CliDiagnostic} is -- see its own Javadoc.
 */
public record ValidationRun(boolean valid, List<FileReport> files, List<CliDiagnostic> errors) {

    /** A run that got as far as validating documents: the verdict is every file's verdict. */
    static ValidationRun of(List<FileReport> files) {
        return new ValidationRun(files.stream().allMatch(FileReport::valid), files, List.of());
    }

    /** A run that never reached a document -- no files, one run-level problem, and exit 2. */
    static ValidationRun failed(Diagnostic.Code code, String message) {
        return new ValidationRun(false, List.of(), List.of(CliDiagnostic.minimal(code, message)));
    }
}
