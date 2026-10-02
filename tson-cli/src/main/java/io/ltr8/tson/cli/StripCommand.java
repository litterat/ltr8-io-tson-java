package io.ltr8.tson.cli;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.compiler.TsonDiagnostics;
import io.ltr8.tson.compiler.TsonSchemaStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code tson strip <schema>} -- writes a schema document's reading form ({@link TsonSchemaStripper}) to
 * standard output: no {@code !!id}, no pins, no {@code @doc} or {@code @comment}, the spec's library shortened,
 * whitespace collapsed. The file is never rewritten, since the result is not a loadable schema.
 */
final class StripCommand {

    private StripCommand() {
    }

    /** @return exit code: 0 written, 1 not a well-formed schema document, 2 the file couldn't be read */
    static int run(Path file) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            System.err.println("cannot read " + file + ": " + e.getMessage());
            return 2;
        }
        String stripped;
        try {
            stripped = TsonSchemaStripper.strip(source);
        } catch (RuntimeException e) {
            Diagnostic syntax = TsonDiagnostics.ofSchemaSyntaxError("", e);
            String at = syntax.schemaPosition().map(StripCommand::location).orElse("");
            System.err.println(file + at + ": " + syntax.message());
            return 1;
        }
        System.out.print(stripped);
        return 0;
    }

    private static String location(SourcePosition position) {
        return ":" + position.line() + ":" + position.column();
    }
}
