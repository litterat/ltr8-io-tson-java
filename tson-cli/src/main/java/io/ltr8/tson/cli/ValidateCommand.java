package io.ltr8.tson.cli;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.Tson;
import io.ltr8.tson.json.Json;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.TsonDocumentPeek;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.base.CanonicalIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code tson validate [--output text|json|tson] <file|->...} -- validates data documents.
 *
 * <p><b>A flat list of files, auto-classified.</b> Each file is a TSON schema document (its header
 * carries {@code !!meta}) or a data document. The schema files are made available through a {@link
 * SchemaSource}, and each data document is handed to {@link Tson#validate(InputStream)}, which
 * works out on its own whether the data's {@code !!schema} selects a schema or whether it's validated
 * schemalessly (base syntax + built-in atoms). This command only turns the argument list into a
 * source + a list of data documents, then renders the diagnostics.
 *
 * <p><b>{@code -} is standard input</b>, always a data document and never classified -- see {@link
 * ValidateInput}. Schemas stay files.
 *
 * <p><b>One {@link ValidationRun} per invocation, whatever the file count.</b> Each data file's
 * verdict is a named {@link FileReport} inside it, so {@code --output json}/{@code tson} emit a
 * single parseable document with the filenames in it rather than reports separated by labels a
 * machine consumer would have to reassemble.
 */
final class ValidateCommand {

    private ValidateCommand() {
    }

    /** A run with no JSON in it -- every input is TSON text, naming its own schema and root type. */
    static int run(List<ValidateInput> inputs, OutputFormat format, PolicyOptions policies) {
        return run(inputs, format, policies, null);
    }

    /**
     * @param binding the schema and root type every JSON input is read against ([TSON-JSON] §3.4), or null
     *                when the run holds no JSON
     * @return exit code: 0 every data file valid, 1 at least one invalid, 2 a usage/classification failure,
     *         69 a document whose schema no file here declares, 70 a document that could not be checked at
     *         all because a construct in its schema is a gap in this library ({@link TsonCli#exitCodeFor})
     */
    static int run(List<ValidateInput> inputs, OutputFormat format, PolicyOptions policies,
                   JsonBinding binding) {
        Map<String, String> schemas = new HashMap<>();
        // The !!id each schema file declares, verbatim and in argument order -- what an unmatched
        // !!schema is reported against. Kept apart from the lookup map, whose keys are canonicalized
        // (scheme and ?sha256= stripped) and so would not be the string the author actually wrote.
        List<String> declaredIds = new ArrayList<>();
        List<ValidateInput> dataInputs = new ArrayList<>();

        // The provided schemas, made available by !!id; the bundled standard library is always served
        // underneath. Tson.validate resolves a data file's !!schema through this. Built before the files are
        // classified because it reads `schemas` only when a document asks for one, and because the run's own
        // policy has to come from the instance that judges -- including on a run that fails classification
        // and judges nothing, whose report carries the same envelope as any other.
        SchemaSource source = uri -> {
            String text = schemas.get(CanonicalIdentity.canonicalize(uri));
            if (text == null) {
                // SchemaFetchException, not an IllegalStateException: this is a source saying it cannot
                // supply a schema, which is the one thing the fetch contract names a type for. Anything else
                // thrown from here would be classified as a fault in this command and rethrown as one.
                throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND,
                        "no schema file on the command line declares that !!id" + supplied(declaredIds), null);
            }
            return text;
        };
        // One Tson for the whole run, so the identifier policy governs the schema files' own declared names
        // at link time and the data documents' names at read time alike -- it is one processor, and a flag
        // that reached only one of the two ends would be a trap.
        Tson tson = Tson.of(policies.applyTo(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))));
        CliPolicy policy = CliPolicy.from(tson.processorPolicy());

        // A --schema naming a file joins the run's schemas first and binds by the !!id it declares, so the
        // rest of the run sees an identity whichever way the binding was given.
        Path schemaFile = binding == null ? null : binding.schemaFile().orElse(null);
        if (schemaFile != null) {
            boolean schema;
            try {
                schema = isSchemaDocument(schemaFile);
            } catch (IOException e) {
                System.out.println(format.render(ValidationRun.failed(policy,
                        Diagnostic.Code.VALIDATION_ERROR, cannotRead(new ValidateInput.OfFile(schemaFile), e))));
                return 2;
            }
            if (!schema) {
                System.out.println(format.render(ValidationRun.failed(policy, Diagnostic.Code.SCHEMA_NOT_FOUND,
                        "--schema \"" + schemaFile + "\" is a file but not a schema document -- its header "
                                + "carries no !!meta, and schemas are TSON text whatever encoding their data is")));
                return 2;
            }
            try {
                binding = new JsonBinding(register(schemaFile, schemas, declaredIds), binding.rootType());
            } catch (RuntimeException e) {
                System.out.println(format.render(ValidationRun.failed(policy,
                        Diagnostic.Code.SCHEMA_ERROR, schemaFile + ": " + e.getMessage())));
                return 2;
            }
        }

        for (ValidateInput input : inputs) {
            // Standard input is a data document by definition -- classification opens the document a second
            // time, and a stream has nothing to reopen. See ValidateInput.
            if (!(input instanceof ValidateInput.OfFile(Path file)) || JsonBinding.isJsonFile(input.name())) {
                // A .json input is data in the other encoding and carries no header to classify by
                // ([TSON-JSON] §3.1); stdin has no name at all. Neither is ever a schema document: schemas
                // are TSON text, whichever encoding the data they govern arrives in.
                dataInputs.add(input);
                continue;
            }
            boolean schema;
            try {
                schema = isSchemaDocument(file);
            } catch (IOException e) {
                System.out.println(format.render(ValidationRun.failed(policy,
                        Diagnostic.Code.VALIDATION_ERROR, cannotRead(input, e))));
                return 2;
            }
            if (schema) {
                if (schemaFile != null && isSameFile(file, schemaFile)) {
                    continue;   // named by --schema as well, and already loaded
                }
                try {
                    register(file, schemas, declaredIds);
                } catch (RuntimeException e) {
                    System.out.println(format.render(ValidationRun.failed(policy,
                            Diagnostic.Code.SCHEMA_ERROR, file + ": " + e.getMessage())));
                    return 2;
                }
            } else {
                dataInputs.add(input);
            }
        }

        if (dataInputs.isEmpty()) {
            System.out.println(format.render(ValidationRun.failed(policy, Diagnostic.Code.VALIDATION_ERROR,
                    "no data files to validate (only schema files were given)")));
            return 2;
        }

        // Every file's report is collected before anything is printed: the envelope's own verdict is the
        // AND across the files, so there is nothing to emit until the last one is in.
        Json json = binding == null ? null
                : Json.of(policies.applyTo(ProcessorConfig.defaults())).withSchemas(tson.schemaRegistry());
        if (binding != null) {
            // The binding is checked before any document is read, so a mistyped --schema or --type is a
            // usage error where the person who typed it can act, rather than an identical-looking verdict
            // per file. `Tson.resolve` registers, so the registry is what the JSON side then loads through.
            int refused = bind(tson, json, binding, schemas, declaredIds, format, policy);
            if (refused != 0) {
                return refused;
            }
        }

        List<FileReport> reports = new ArrayList<>();
        for (ValidateInput dataInput : dataInputs) {
            List<CliDiagnostic> errors;
            // IOException only: an unreadable file is that file's own problem, so it renders as a verdict
            // and the run carries on to the next one. A RuntimeException is not -- Tson.validate returns
            // every document-level failure as a Diagnostic and rethrows only a fault in the library, so
            // catching it here would put that fault back into a per-file "invalid" verdict, which is what
            // it went out of its way to avoid. It propagates to TsonCli's fault handler instead.
            try (InputStream in = dataInput.open()) {
                errors = (isJson(dataInput, binding)
                        ? json.validate(in, binding.schema(), binding.rootType())
                        : tson.validate(in)).stream().map(CliDiagnostic::from).toList();
            } catch (IOException e) {
                errors = List.of(CliDiagnostic.minimal(Diagnostic.Code.VALIDATION_ERROR,
                        cannotRead(dataInput, e)));
            }
            reports.add(FileReport.of(dataInput.name(), errors));
        }

        ValidationRun run = ValidationRun.of(policy, reports);
        System.out.println(format.render(run));
        return run.outcome() == Outcome.VALID ? 0 : TsonCli.exitCodeFor(reports.stream()
                .flatMap(report -> report.errors().stream()).map(CliDiagnostic::code).toList());
    }

    /**
     * What the supplied schema files actually declare, appended to an unmatched {@code !!schema}.
     *
     * <p>A schema file is matched by the {@code !!id} inside it, never by its filename ([TSON-DATA]
     * §2.2.1), so the mismatch an author hits most is passing the right file with the wrong identity in
     * it -- and a bare "not found" then reads as though the file were missing, while they are looking
     * straight at it. Listing the identities puts the two strings side by side, which is usually the whole
     * diagnosis.
     */
    private static String supplied(List<String> declaredIds) {
        if (declaredIds.isEmpty()) {
            return " (no schema files were given)";
        }
        return " (the schema files given declare: " + String.join(", ", declaredIds) + ")";
    }

    /**
     * Whether this input is read as JSON. A {@code .json} name says so; standard input has no name, so the
     * binding says so instead -- the two flags exist for nothing else, a TSON document naming its own.
     */
    private static boolean isJson(ValidateInput input, JsonBinding binding) {
        return binding != null
                && (JsonBinding.isJsonFile(input.name()) || input instanceof ValidateInput.OfStdin);
    }

    /**
     * Resolves the bound schema and checks the root type, before any document is read.
     *
     * <p>By here a {@code --schema} file has been loaded and replaced by the identity it declares, so a value
     * that is still not an identifying URI names no file either -- a mistyped path, most often. Every failure
     * is the command line's rather than a document's, and each prints as a run-level failure and exits 2: that
     * value, a schema identity no file on the command line declares, and a root type that schema does not
     * declare. Reporting them per file instead would say the documents were invalid, which
     * is a verdict on the wrong thing -- and would say it once per file for one typo. The first two list the
     * identities the schema files do declare, which is usually the value that was meant.
     */
    private static int bind(Tson tson, Json json, JsonBinding binding, Map<String, String> schemas,
                            List<String> declaredIds, OutputFormat format, CliPolicy policy) {
        String identity;
        try {
            identity = CanonicalIdentity.canonicalize(binding.schema());
        } catch (SchemaValidationException e) {
            System.out.println(format.render(ValidationRun.failed(policy, Diagnostic.Code.SCHEMA_NOT_FOUND,
                    "--schema \"" + binding.schema() + "\" is neither a file nor a schema identity ("
                            + e.getMessage() + ")" + supplied(declaredIds))));
            return 2;
        }
        String text = schemas.get(identity);
        if (text == null) {
            System.out.println(format.render(ValidationRun.failed(policy, Diagnostic.Code.SCHEMA_NOT_FOUND,
                    "--schema \"" + binding.schema() + "\": no schema file on the command line declares "
                            + "that !!id" + supplied(declaredIds))));
            return 2;
        }
        try {
            if (tson.schemaRegistry().getByCanonicalIdentity(identity).isEmpty()) {
                tson.resolve(text);
            }
        } catch (RuntimeException e) {
            System.out.println(format.render(ValidationRun.failed(policy, Diagnostic.Code.SCHEMA_ERROR,
                    binding.schema() + ": " + e.getMessage())));
            return 2;
        }
        List<Diagnostic> named = json.validate("", binding.schema(), binding.rootType());
        if (named.stream().anyMatch(d -> d.code() == Diagnostic.Code.UNKNOWN_TYPE)) {
            System.out.println(format.render(ValidationRun.failed(policy, Diagnostic.Code.UNKNOWN_TYPE,
                    "--type \"" + binding.rootType() + "\": " + named.stream()
                            .filter(d -> d.code() == Diagnostic.Code.UNKNOWN_TYPE)
                            .findFirst().orElseThrow().message())));
            return 2;
        }
        return 0;
    }

    /**
     * Why a document could not be read, named.
     *
     * <p>An {@link IOException} from the file system usually carries the offending path as its whole
     * message and the failure <i>kind</i> only in its type, so the obvious {@code "cannot read " + file +
     * ": " + e.getMessage()} renders as {@code cannot read x: x} -- the path twice and no reason. The
     * common kinds are spelled out; anything else falls back to whatever the exception does say.
     */
    private static String cannotRead(ValidateInput input, IOException e) {
        String reason = switch (e) {
            case NoSuchFileException ignored -> "no such file";
            case AccessDeniedException ignored -> "permission denied";
            case FileSystemException fs when fs.getReason() != null -> fs.getReason();
            default -> e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        };
        return "cannot read " + input.name() + ": " + reason;
    }

    /** The three bundled standard-library identities, which {@code ProcessorConfig} always serves from its own resources. */
    private static boolean isBundledId(String id) {
        return id.equals(TsonBundledSchemas.META_KERNEL_ID)
                || id.equals(TsonBundledSchemas.META_ID)
                || id.equals(TsonBundledSchemas.CORE_ID);
    }

    /**
     * Loads one schema file into the run and returns the {@code !!id} it declares. A file declaring a bundled
     * schema's identity is noted and left out, since the standard library is always served underneath.
     */
    private static String register(Path file, Map<String, String> schemas, List<String> declaredIds) {
        String text = Io.readFile(file);
        String id = new TsonSchemaParser(text).parseSchemaDocument().id().orElseThrow(() ->
                new IllegalArgumentException("schema " + file + " has no !!id"));
        if (isBundledId(id)) {
            System.err.println("note: " + file + " declares the built-in schema id \"" + id
                    + "\" -- overriding meta-kernel/meta/core is not supported; ignoring this file");
        } else {
            // Key by canonical identity so a data file's plain !!schema resolves against a schema whose
            // !!id carries a ?sha256= pin (the hash is not identity, §2.2.1).
            schemas.put(CanonicalIdentity.canonicalize(id), text);
            declaredIds.add(id);
        }
        return id;
    }

    /** Whether two paths are one file, however each was spelled; false when either cannot be checked. */
    private static boolean isSameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    /** A file whose header carries {@code !!meta} is a schema document ([TSON-SCHEMA] §12.1 requires one). */
    private static boolean isSchemaDocument(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return TsonDocumentPeek.of(in).isSchemaDocument();
        } catch (UncheckedIOException e) {
            return false;   // unreadable -> data, so Tson.validate reports the real error rather than this
        }
    }
}
