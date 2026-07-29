package io.ltr8.tson.cli;

import java.util.List;

/**
 * The result of one {@code validate}/{@code compile} invocation -- {@code valid} true with an empty
 * {@code errors} list, or false with exactly one entry, since this CLI doesn't yet collect multiple
 * errors (tracked in {@code STRUCTURED-OUTPUT.md}; this class catches a single exception from the
 * existing fail-fast stack, it doesn't run a real multi-error pass). Shape matches {@code
 * diagnostics.tn1} (see {@link OutputFormat}) field for field, so {@link
 * io.ltr8.tson.compiler.mapper.TsonMapperWriter#toTson} and that schema's own compiled reader agree.
 *
 * <p>Public for the same reason {@link CliDiagnostic} is -- see its own Javadoc.
 */
public record ValidationReport(boolean valid, List<CliDiagnostic> errors) {

    static ValidationReport ok() {
        return new ValidationReport(true, List.of());
    }

    static ValidationReport failed(String code, String message) {
        return new ValidationReport(false, List.of(new CliDiagnostic(code, message)));
    }
}
