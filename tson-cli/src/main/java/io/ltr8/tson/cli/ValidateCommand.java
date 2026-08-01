package io.ltr8.tson.cli;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonUnsupportedDocumentException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchemaRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code tson validate [--output text|json|tson] <file>...} -- validates data files.
 *
 * <p><b>A flat list of files, auto-classified.</b> Each file is a TSON schema document (its header
 * carries {@code !!meta}) or a data document. The schema files are made available through a {@link
 * TsonSchemaSource}, and each data file is handed to {@link Tson#validate(InputStream)}, which works
 * out on its own whether the data's {@code !!schema} selects a schema or whether it's validated
 * schemalessly (base syntax + built-in atoms). This command only turns the argument list into a
 * source + a list of data files, then renders the diagnostics.
 */
final class ValidateCommand {

    private ValidateCommand() {
    }

    /** @return exit code: 0 every data file valid, 1 at least one invalid, 2 a usage/classification failure */
    static int run(List<Path> files, OutputFormat format) {
        Map<String, String> schemas = new HashMap<>();
        List<Path> dataFiles = new ArrayList<>();
        for (Path file : files) {
            boolean schema;
            try {
                schema = isSchemaDocument(file);
            } catch (IOException e) {
                System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR,
                        "cannot read " + file + ": " + e.getMessage())));
                return 2;
            }
            if (schema) {
                try {
                    String text = Io.readFile(file);
                    String id = new TsonSchemaParser(text).parseSchemaDocument().id().orElseThrow(() ->
                            new IllegalArgumentException("schema " + file + " has no !!id"));
                    if (isBundledId(id)) {
                        System.err.println("note: " + file + " declares the built-in schema id \"" + id
                                + "\" -- overriding meta-kernel/meta/core is not supported; ignoring this file");
                    } else {
                        // Key by canonical identity so a data file's plain !!schema resolves against a
                        // schema whose !!id carries a ?sha256= pin (the hash is not identity, §2.2.1).
                        schemas.put(TsonSchemaRegistry.canonicalIdentity(id), text);
                    }
                } catch (RuntimeException e) {
                    System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.SCHEMA_ERROR,
                            file + ": " + e.getMessage())));
                    return 2;
                }
            } else {
                dataFiles.add(file);
            }
        }

        if (dataFiles.isEmpty()) {
            System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR,
                    "no data files to validate (only schema files were given)")));
            return 2;
        }

        // The provided schemas, made available by !!id; the bundled standard library is always served
        // underneath. Tson.validate resolves a data file's !!schema through this.
        TsonSchemaSource source = uri -> {
            String text = schemas.get(TsonSchemaRegistry.canonicalIdentity(uri));
            if (text == null) {
                throw new IllegalStateException("no schema file provided for !!schema \"" + uri + "\"");
            }
            return text;
        };
        Tson tson = Tson.builder().schemaSource(source).build();

        boolean allValid = true;
        for (Path dataFile : dataFiles) {
            if (dataFiles.size() > 1) {
                System.out.println("# " + dataFile);
            }
            List<CliDiagnostic> errors;
            try (InputStream in = Files.newInputStream(dataFile)) {
                errors = tson.validate(in).stream().map(CliDiagnostic::from).toList();
            } catch (RuntimeException | IOException e) {
                allValid = false;
                System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR, e.getMessage())));
                continue;
            }
            if (!errors.isEmpty()) {
                allValid = false;
            }
            System.out.println(format.render(new ValidationReport(errors.isEmpty(), errors)));
        }
        return allValid ? 0 : 1;
    }

    /** The three bundled standard-library identities, which {@code TsonConfig} always serves from its own resources. */
    private static boolean isBundledId(String id) {
        return id.equals(TsonBundledSchemas.META_KERNEL_ID)
                || id.equals(TsonBundledSchemas.META_ID)
                || id.equals(TsonBundledSchemas.CORE_ID);
    }

    /** A file whose header carries {@code !!meta} is a schema document (per the data grammar's own rejection of it). */
    private static boolean isSchemaDocument(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            new TsonDataStream(in).next();   // reads the header; DocumentStart for data
            return false;
        } catch (TsonUnsupportedDocumentException e) {
            return true;
        } catch (RuntimeException e) {
            return false;   // malformed as data -> let Tson.validate report the real error
        }
    }
}
