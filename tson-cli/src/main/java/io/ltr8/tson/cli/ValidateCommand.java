package io.ltr8.tson.cli;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.TsonValueReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code tson validate --type <name> [--output text|json|tson] <schema> <data...>} -- compiles
 * {@code schema} (DOM mode: an arbitrary user schema's own Java shape, if any, isn't known to this
 * CLI), gets the compiled reader for {@code --type}, and reads each data file against it.
 *
 * <p><b>Always collects every problem in a file, not just the first</b> -- each data file is read
 * through a collecting {@link TsonReadContext}, so a single {@code validate} run surfaces every
 * independent problem in that file in one pass, not just the first one found. There's no separate
 * fail-fast mode/flag: collecting *is* the default, since gating it behind a flag would just add a
 * mode nobody uses. A failure that happens outside any single read at all (the schema itself doesn't
 * compile, or {@code --type} names something that doesn't exist) still reports as one infrastructure-
 * level {@link CliDiagnostic}, via {@link ValidationReport#failed}.
 *
 * <p><b>Requires an explicit {@code --type}</b> because there's no {@code !!schema}-header
 * auto-selection yet (tracked in {@code BACKLOG.md}'s "Front door / ergonomics") -- a TSON schema
 * document is a map of many type declarations, not one canonical root type the way a JSON Schema
 * document is, so there's no other way to say which one a data file should be read against.
 */
final class ValidateCommand {

    private ValidateCommand() {
    }

    /** @return exit code: 0 every file valid, 1 at least one file invalid, 2 the schema/type itself couldn't be loaded */
    static int run(Path schemaFile, String typeName, List<Path> dataFiles, OutputFormat format) {
        TsonCompiledMetaSchema compiledSchema;
        try {
            Tson tson = Tson.builder().build();
            compiledSchema = tson.compile(Io.readFile(schemaFile), TsonSchemaCompiler.dom());
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.SCHEMA_ERROR, e.getMessage())));
            return 2;
        }

        TsonValueReader<?> reader;
        try {
            reader = compiledSchema.compiledSchema().get(typeName);
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.UNKNOWN_TYPE, e.getMessage())));
            return 2;
        }

        boolean allValid = true;
        for (Path dataFile : dataFiles) {
            if (dataFiles.size() > 1) {
                System.out.println("# " + dataFile);
            }
            try (InputStream in = Files.newInputStream(dataFile)) {
                TsonReadContext ctx = TsonReadContext.collecting(in);
                reader.read(ctx);
                List<CliDiagnostic> errors = ctx.diagnostics().stream().map(CliDiagnostic::from).toList();
                if (!errors.isEmpty()) {
                    allValid = false;
                }
                System.out.println(format.render(new ValidationReport(errors.isEmpty(), errors)));
            } catch (RuntimeException | IOException e) {
                allValid = false;
                System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR, e.getMessage())));
            }
        }
        return allValid ? 0 : 1;
    }
}
