package io.ltr8.tson.cli;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code tson compile [--output text|json|tson] <schema>} -- resolves, links, registers, and
 * compiles {@code schema} (DOM mode) and reports whether it compiled cleanly. Unlike {@code
 * validate}, needs no {@code --type}: this checks the whole schema document, not one type's own
 * data against a value.
 *
 * <p>Reports <b>every</b> problem the schema has at the first phase that finds any, each naming the
 * declaration it came from and where that declaration is in the source -- the same treatment {@code
 * validate} gives a data file. {@link Tson#validateSchema} owns all of that, including the phase
 * boundary; this command only renders the result and picks an exit code.
 */
final class CompileCommand {

    private CompileCommand() {
    }

    /**
     * @return exit code: 0 compiled cleanly, 1 it didn't, 69 an {@code !!import}/{@code !!meta} of its own
     *         could not be obtained, so it was never wholly read, 70 a construct in it is a gap in this
     *         library ({@link TsonCli#exitCodeFor})
     */
    static int run(Path schemaFile, OutputFormat format, PolicyOptions policies) {
        Tson tson = policies.applyTo(Tson.builder()).build();
        // Read off the Tson that judged, not rebuilt from a default: a schema's declared names face
        // [TSON-DATA] §8.2 at link time, so this run can refuse one, and a refusal is only interpretable
        // beside the policy that produced it.
        CliPolicy policy = CliPolicy.from(tson.processorPolicy());
        List<Diagnostic> problems;
        try {
            problems = tson.validateSchema(Io.readFile(schemaFile));
        } catch (UncheckedIOException e) {
            // Unreadable file: this file's own verdict, not a library fault. Anything else propagates to
            // TsonCli's own handler and exit 70, which is what keeps a bug distinguishable from a bad schema.
            System.out.println(format.render(
                    ValidationReport.failed(policy, Diagnostic.Code.SCHEMA_ERROR, e.getMessage())));
            return 1;
        }
        if (problems.isEmpty()) {
            System.out.println(format.render(ValidationReport.ok(policy)));
            return 0;
        }
        List<CliDiagnostic> errors = problems.stream().map(CliDiagnostic::from).toList();
        System.out.println(format.render(new ValidationReport(Outcome.of(errors), policy, errors)));
        return TsonCli.exitCodeFor(problems.stream().map(Diagnostic::code).toList());
    }
}
