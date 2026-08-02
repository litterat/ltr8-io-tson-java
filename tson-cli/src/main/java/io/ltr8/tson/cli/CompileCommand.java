package io.ltr8.tson.cli;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;

import java.nio.file.Path;

/**
 * {@code tson compile [--output text|json|tson] <schema>} -- resolves, links, registers, and
 * compiles {@code schema} (DOM mode) and reports whether it compiled cleanly. Unlike {@code
 * validate}, needs no {@code --type}: this checks the whole schema document, not one type's own
 * data against a value.
 */
final class CompileCommand {

    private CompileCommand() {
    }

    /** @return exit code: 0 compiled cleanly, 1 it didn't */
    static int run(Path schemaFile, OutputFormat format) {
        try {
            Tson tson = Tson.builder().build();
            tson.treeRegistry().compile(tson.resolve(Io.readFile(schemaFile)));
            System.out.println(format.render(ValidationReport.ok()));
            return 0;
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.SCHEMA_ERROR, e.getMessage())));
            return 1;
        }
    }
}
