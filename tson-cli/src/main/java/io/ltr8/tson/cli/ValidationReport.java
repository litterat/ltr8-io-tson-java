package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;

import java.util.List;

/**
 * The result of one {@code validate}/{@code compile} invocation -- {@code valid} true with an empty
 * {@code errors} list, or false with every problem a collecting {@code TsonReadContext} found in a
 * single pass over one file (see {@link ValidateCommand}), or exactly one entry for an
 * infrastructure-level failure ({@link #failed}) that happens outside any read at all -- the schema
 * itself didn't compile, or a requested type name doesn't exist. Shape matches {@code
 * diagnostics.tn}'s own {@code validation_report} field for field (see {@link OutputFormat}), so
 * {@code TsonObjectWriter#toTson} and that schema's own compiled reader agree.
 *
 * <p><b>{@code policy} is here for the reason it is on {@link ValidationRun}</b>: a schema's own declared
 * names face [TSON-DATA] §8.2 at link time, so {@code compile} can refuse one, and a refusal is only
 * interpretable beside the policy that produced it.
 *
 * <p>Public for the same reason {@link CliDiagnostic} is -- see its own Javadoc.
 */
public record ValidationReport(boolean valid, CliPolicy policy, List<CliDiagnostic> errors) {

    static ValidationReport ok(CliPolicy policy) {
        return new ValidationReport(true, policy, List.of());
    }

    static ValidationReport failed(CliPolicy policy, Diagnostic.Code code, String message) {
        return new ValidationReport(false, policy, List.of(CliDiagnostic.minimal(code, message)));
    }
}
