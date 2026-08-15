package io.ltr8.tson.cli;

import java.util.List;

/**
 * One data document's verdict within a {@link ValidationRun} -- the file it came from, whether it
 * validated, and every problem a single collecting pass over it found.
 *
 * <p>Flat rather than a {@code file} wrapped around a {@link ValidationReport}, so a consumer reads
 * {@code valid}/{@code errors} at the same depth whichever of the two shapes it holds; {@code
 * validate} and {@code compile} then differ only in whether their reports are named.
 *
 * <p>Public for the same reason {@link CliDiagnostic} is -- see its own Javadoc.
 */
public record FileReport(String file, boolean valid, List<CliDiagnostic> errors) {

    static FileReport of(String file, List<CliDiagnostic> errors) {
        return new FileReport(file, errors.isEmpty(), errors);
    }
}
