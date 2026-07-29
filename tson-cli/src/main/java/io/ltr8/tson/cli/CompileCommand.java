package io.ltr8.tson.cli;

import io.ltr8.tson.parser.compiler.ValueReaderFactoryRegistry;
import io.ltr8.tson.schema.TsonLinkedSchema;

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
            StandardLibrary.Bootstrapped stdlib = StandardLibrary.bootstrap();
            TsonLinkedSchema linked = StandardLibrary.resolveUserSchema(stdlib, Io.readFile(schemaFile));
            StandardLibrary.compile(linked, ValueReaderFactoryRegistry.dom());
            System.out.println(format.render(ValidationReport.ok()));
            return 0;
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationReport.failed("COMPILE_ERROR", e.getMessage())));
            return 1;
        }
    }
}
