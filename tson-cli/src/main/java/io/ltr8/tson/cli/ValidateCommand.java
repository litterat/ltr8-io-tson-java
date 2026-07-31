package io.ltr8.tson.cli;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code tson validate [--type <name>] [--output text|json|tson] <schema> <data...>} -- compiles
 * {@code schema} (DOM mode: an arbitrary user schema's own Java shape, if any, isn't known to this
 * CLI) and reads each data file against one of its types.
 *
 * <p><b>Self-describing data</b>: a data document that opens with a root type-ref (e.g. {@code !person
 * { ... }}) selects its own type -- no {@code --type} needed. If it also carries a {@code !!schema}
 * header, that URI is verified against the compiled schema's own {@code !!id} (a mismatch is a
 * per-file {@code SCHEMA_ERROR}, exit 1 -- the document claims conformance to a schema it isn't being
 * checked against). {@code --type <name>} stays available as an explicit override, and is still the
 * only way to validate a plain, non-self-describing {@code { ... }} document.
 *
 * <p><b>Always collects every problem in a file, not just the first</b> -- each data file is read
 * through a collecting {@link TsonReadContext}, so a single {@code validate} run surfaces every
 * independent problem in that file in one pass. A failure outside any single read (the schema itself
 * doesn't compile, {@code --type} names something that doesn't exist) reports as one infrastructure-
 * level {@link CliDiagnostic}, via {@link ValidationReport#failed}.
 */
final class ValidateCommand {

    private ValidateCommand() {
    }

    /**
     * @param typeName an explicit type override, or {@code null} to take each data file's own root type-ref
     * @return exit code: 0 every file valid, 1 at least one file invalid, 2 the schema/{@code --type} itself couldn't be loaded
     */
    static int run(Path schemaFile, String typeName, List<Path> dataFiles, OutputFormat format) {
        Tson tson;
        TsonCompiledMetaSchema compiledSchema;
        try {
            tson = Tson.builder().build();
            compiledSchema = tson.compile(Io.readFile(schemaFile), TsonSchemaCompiler.dom());
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.SCHEMA_ERROR, e.getMessage())));
            return 2;
        }
        String schemaId = compiledSchema.schema().id();

        // An explicit --type resolves once, up front: an unknown name is a load error (exit 2), not a
        // per-file data problem. Deriving the type from a data file's own root type-ref happens per file.
        TsonValueReader<?> fixedReader = null;
        if (typeName != null) {
            try {
                fixedReader = compiledSchema.compiledSchema().get(typeName);
            } catch (RuntimeException e) {
                System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.UNKNOWN_TYPE, e.getMessage())));
                return 2;
            }
        }

        boolean allValid = true;
        for (Path dataFile : dataFiles) {
            if (dataFiles.size() > 1) {
                System.out.println("# " + dataFile);
            }
            try (InputStream in = Files.newInputStream(dataFile)) {
                TsonDataStream stream = new TsonDataStream(in);
                DocumentStart docStart = (DocumentStart) stream.next();

                // A declared !!schema must name the schema we're actually checking against.
                Optional<String> declared = docStart.schema();
                if (declared.isPresent() && !schemaMatches(tson, declared.get(), schemaId)) {
                    allValid = false;
                    System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.SCHEMA_ERROR,
                            "data declares !!schema \"" + declared.get() + "\" but the schema provided is \"" + schemaId + "\"")));
                    continue;
                }

                TsonValueReader<?> reader = fixedReader;
                if (reader == null) {
                    String derived = stream.peek() instanceof TypeRef tr ? tr.name() : null;
                    if (derived == null) {
                        allValid = false;
                        System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR,
                                "no --type given and the data has no root type-ref (e.g. `!person`) to select one")));
                        continue;
                    }
                    try {
                        reader = compiledSchema.compiledSchema().get(derived);
                    } catch (RuntimeException e) {
                        allValid = false;
                        System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.UNKNOWN_TYPE, e.getMessage())));
                        continue;
                    }
                }

                TsonReadContext ctx = TsonReadContext.collecting(stream);
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

    /**
     * Does {@code declaredUri} (a data file's own {@code !!schema}) name the same schema as {@code schemaId}
     * (the compiled schema's own {@code !!id})? Canonicalized via the registry the schema was just registered
     * in; a malformed {@code declaredUri} is treated as a mismatch rather than a separate error.
     */
    private static boolean schemaMatches(Tson tson, String declaredUri, String schemaId) {
        try {
            return tson.schemaRegistry().get(declaredUri)
                    .map(linked -> linked.schema().id().equals(schemaId))
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
