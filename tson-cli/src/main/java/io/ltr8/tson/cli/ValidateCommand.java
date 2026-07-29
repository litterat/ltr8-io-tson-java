package io.ltr8.tson.cli;

import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.parser.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.parser.compiler.ValueReaderFactoryRegistry;
import io.ltr8.tson.schema.TsonLinkedSchema;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code tson validate --type <name> [--output text|json|tson] <schema> <data...>} -- compiles
 * {@code schema} (DOM mode: an arbitrary user schema's own Java shape, if any, isn't known to this
 * CLI), gets the compiled reader for {@code --type}, and reads each data file against it.
 *
 * <p><b>No multi-error collection yet</b> (tracked in {@code STRUCTURED-OUTPUT.md}) -- the existing
 * reader/resolver stack is fail-fast, so each file either reads cleanly or throws on the first
 * problem found; that single caught exception becomes this file's one {@link CliDiagnostic}.
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
            StandardLibrary.Bootstrapped stdlib = StandardLibrary.bootstrap();
            TsonLinkedSchema linked = StandardLibrary.resolveUserSchema(stdlib, Io.readFile(schemaFile));
            compiledSchema = StandardLibrary.compile(linked, ValueReaderFactoryRegistry.dom());
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationReport.failed("SCHEMA_ERROR", e.getMessage())));
            return 2;
        }

        TsonValueReader<?> reader;
        try {
            reader = compiledSchema.compiledSchema().get(typeName);
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationReport.failed("UNKNOWN_TYPE", e.getMessage())));
            return 2;
        }

        boolean allValid = true;
        for (Path dataFile : dataFiles) {
            if (dataFiles.size() > 1) {
                System.out.println("# " + dataFile);
            }
            try {
                reader.read(new TsonDataParser(Io.readFile(dataFile)).parseDocument().root());
                System.out.println(format.render(ValidationReport.ok()));
            } catch (RuntimeException e) {
                allValid = false;
                System.out.println(format.render(ValidationReport.failed("VALIDATION_ERROR", e.getMessage())));
            }
        }
        return allValid ? 0 : 1;
    }
}
