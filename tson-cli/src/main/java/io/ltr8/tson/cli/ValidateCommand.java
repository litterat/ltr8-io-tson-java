package io.ltr8.tson.cli;

import io.ltr8.tson.Tson;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDocumentHeader;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonCanonicalIdentity;

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
 * TsonSchemaSource}, and each data document is handed to {@link Tson#validate(InputStream)}, which
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

    /**
     * @return exit code: 0 every data file valid, 1 at least one invalid, 2 a usage/classification failure,
     *         69 a document whose schema no file here declares, 70 a document that could not be checked at
     *         all because a construct in its schema is a gap in this library ({@link TsonCli#exitCodeFor})
     */
    static int run(List<ValidateInput> inputs, OutputFormat format, PolicyOptions policies) {
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
        TsonSchemaSource source = uri -> {
            String text = schemas.get(TsonCanonicalIdentity.canonicalize(uri));
            if (text == null) {
                // TsonSchemaFetchException, not an IllegalStateException: this is a source saying it cannot
                // supply a schema, which is the one thing the fetch contract names a type for. Anything else
                // thrown from here would be classified as a fault in this command and rethrown as one.
                throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_FOUND,
                        "no schema file on the command line declares that !!id" + supplied(declaredIds), null);
            }
            return text;
        };
        // One Tson for the whole run, so the identifier policy governs the schema files' own declared names
        // at link time and the data documents' names at read time alike -- it is one processor, and a flag
        // that reached only one of the two ends would be a trap.
        Tson tson = policies.applyTo(Tson.builder().schemaSource(source)).build();
        CliPolicy policy = CliPolicy.from(tson.processorPolicy(), tson.limitsPolicy());

        for (ValidateInput input : inputs) {
            // Standard input is a data document by definition -- classification opens the document a second
            // time, and a stream has nothing to reopen. See ValidateInput.
            if (!(input instanceof ValidateInput.OfFile(Path file))) {
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
                        schemas.put(TsonCanonicalIdentity.canonicalize(id), text);
                        declaredIds.add(id);
                    }
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
        List<FileReport> reports = new ArrayList<>();
        for (ValidateInput dataInput : dataInputs) {
            List<CliDiagnostic> errors;
            // IOException only: an unreadable file is that file's own problem, so it renders as a verdict
            // and the run carries on to the next one. A RuntimeException is not -- Tson.validate returns
            // every document-level failure as a Diagnostic and rethrows only a fault in the library, so
            // catching it here would put that fault back into a per-file "invalid" verdict, which is what
            // it went out of its way to avoid. It propagates to TsonCli's fault handler instead.
            try (InputStream in = dataInput.open()) {
                errors = tson.validate(in).stream().map(CliDiagnostic::from).toList();
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

    /** The three bundled standard-library identities, which {@code TsonConfig} always serves from its own resources. */
    private static boolean isBundledId(String id) {
        return id.equals(TsonBundledSchemas.META_KERNEL_ID)
                || id.equals(TsonBundledSchemas.META_ID)
                || id.equals(TsonBundledSchemas.CORE_ID);
    }

    /** A file whose header carries {@code !!meta} is a schema document ([TSON-SCHEMA] §12.1 requires one). */
    private static boolean isSchemaDocument(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return TsonDocumentHeader.peek(in).isSchemaDocument();
        } catch (UncheckedIOException e) {
            return false;   // unreadable -> data, so Tson.validate reports the real error rather than this
        }
    }
}
