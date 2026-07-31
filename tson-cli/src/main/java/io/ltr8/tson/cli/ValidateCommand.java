package io.ltr8.tson.cli;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.SchemalessValidator;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonUnsupportedDocumentException;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.TypeRef;
import io.ltr8.tson.schema.TsonLinkedSchema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code tson validate [--type <name>] [--output text|json|tson] <file>...} -- validates data files.
 *
 * <p><b>A flat list of files, auto-classified.</b> Each file is a TSON schema document (its header
 * carries {@code !!meta}) or a data document. The schema files are made available through a {@link
 * TsonSchemaSource}, and each data file is validated by resolving its own {@code !!schema} directive
 * through the normal loader -- the {@code !!schema} URI selects the schema, so there's no separate
 * "which schema" argument. A data file with no {@code !!schema} is validated <b>schemalessly</b>
 * (Class 1): base syntax plus any built-in / core-vocabulary typed atom (see {@link
 * SchemalessValidator}); {@code --type} does not apply there.
 *
 * <p>Always collects every problem in a file, not just the first. A failure outside a single read (a
 * schema that doesn't compile, an unknown type, an unresolvable {@code !!schema}) reports as one
 * infrastructure-level {@link CliDiagnostic}, via {@link ValidationReport#failed}.
 */
final class ValidateCommand {

    private ValidateCommand() {
    }

    /**
     * @param typeName an explicit type override for schema-driven data, or {@code null} to take each
     *                 data file's own root type-ref
     * @return exit code: 0 every data file valid, 1 at least one invalid, 2 a usage/classification failure
     */
    static int run(List<Path> files, String typeName, OutputFormat format) {
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
                    schemas.put(id, text);
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

        // The provided schemas, made available by !!id; the bundled standard library is always
        // served underneath. A data file's !!schema resolves through this via the loader.
        TsonSchemaSource source = uri -> {
            String text = schemas.get(uri);
            if (text == null) {
                throw new IllegalStateException("no schema file provided for !!schema \"" + uri + "\"");
            }
            return text;
        };
        Tson tson = Tson.builder().schemaSource(source).build();
        Map<String, TsonCompiledMetaSchema> domCache = new HashMap<>();

        boolean allValid = true;
        for (Path dataFile : dataFiles) {
            if (dataFiles.size() > 1) {
                System.out.println("# " + dataFile);
            }
            try {
                allValid &= validateDataFile(tson, dataFile, typeName, format, domCache);
            } catch (RuntimeException | IOException e) {
                allValid = false;
                System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR, e.getMessage())));
            }
        }
        return allValid ? 0 : 1;
    }

    /** A file whose header carries {@code !!meta} is a schema document (per the data grammar's own rejection of it). */
    private static boolean isSchemaDocument(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            new TsonDataStream(in).next();   // reads the header; DocumentStart for data
            return false;
        } catch (TsonUnsupportedDocumentException e) {
            return true;
        } catch (RuntimeException e) {
            return false;   // malformed as data -> let schemaless validation report the real error
        }
    }

    private static boolean validateDataFile(Tson tson, Path dataFile, String typeName, OutputFormat format,
                                            Map<String, TsonCompiledMetaSchema> domCache) throws IOException {
        Optional<String> declaredSchema;
        try (InputStream in = Files.newInputStream(dataFile)) {
            TsonDataStream stream = new TsonDataStream(in);
            DocumentStart docStart = (DocumentStart) stream.next();
            declaredSchema = docStart.schema();
            if (declaredSchema.isPresent()) {
                return validateAgainstSchema(tson, stream, declaredSchema.get(), typeName, format, domCache);
            }
        } catch (TsonUnsupportedDocumentException e) {
            System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR,
                    dataFile + " is a schema document, not data")));
            return false;
        }

        // No !!schema: a schemaless (Class 1) base-syntax + built-in-atom check. Re-read from the top.
        List<CliDiagnostic> errors;
        try (InputStream in = Files.newInputStream(dataFile)) {
            errors = SchemalessValidator.validate(in).stream().map(CliDiagnostic::from).toList();
        }
        System.out.println(format.render(new ValidationReport(errors.isEmpty(), errors)));
        return errors.isEmpty();
    }

    private static boolean validateAgainstSchema(Tson tson, TsonDataStream stream, String schemaUri,
                                                 String typeName, OutputFormat format,
                                                 Map<String, TsonCompiledMetaSchema> domCache) {
        TsonCompiledMetaSchema compiled;
        try {
            compiled = domCompiledSchema(tson, schemaUri, domCache);
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.SCHEMA_ERROR, e.getMessage())));
            return false;
        }

        String type = typeName != null ? typeName
                : (stream.peek() instanceof TypeRef tr ? tr.name() : null);
        if (type == null) {
            System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR,
                    "no --type given and the data has no root type-ref (e.g. `!person`) to select one")));
            return false;
        }

        TsonValueReader<?> reader;
        try {
            reader = compiled.compiledSchema().get(type);
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationReport.failed(Diagnostic.Code.UNKNOWN_TYPE, e.getMessage())));
            return false;
        }

        TsonReadContext ctx = TsonReadContext.collecting(stream);
        reader.read(ctx);
        List<CliDiagnostic> errors = ctx.diagnostics().stream().map(CliDiagnostic::from).toList();
        System.out.println(format.render(new ValidationReport(errors.isEmpty(), errors)));
        return errors.isEmpty();
    }

    /**
     * The DOM-mode compiled schema for {@code schemaUri}, compiled once and cached. The loader
     * resolves+registers the schema (and its imports) through the {@link TsonSchemaSource} -- throwing
     * if nothing can provide it -- then it's compiled in DOM mode (not the loader's own
     * object-binding mode, which would need a Java class per type).
     */
    private static TsonCompiledMetaSchema domCompiledSchema(Tson tson, String schemaUri,
                                                            Map<String, TsonCompiledMetaSchema> domCache) {
        TsonCompiledMetaSchema cached = domCache.get(schemaUri);
        if (cached != null) {
            return cached;
        }
        tson.loader().load(schemaUri);   // resolve + register via the source (throws if unavailable)
        TsonLinkedSchema linked = tson.schemaRegistry().get(schemaUri).orElseThrow(() ->
                new IllegalStateException("schema \"" + schemaUri + "\" resolved but is not registered"));
        TsonCompiledMetaSchema compiled = tson.compile(linked, TsonSchemaCompiler.dom());
        domCache.put(schemaUri, compiled);
        return compiled;
    }
}
