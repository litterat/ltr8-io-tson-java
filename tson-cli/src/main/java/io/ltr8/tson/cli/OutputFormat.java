package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.TsonWriteException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * How a {@link ValidationRun} or a {@link ValidationReport} is printed -- {@link #TEXT} for a human
 * reading a terminal, {@link #JSON} for a script/agent (and interop with the JSON-Schema/pydantic
 * tooling ecosystem), and {@link #TSON} for a real, schema-validated TSON document: this CLI's own
 * diagnostics dogfooding the library, not just JSON with a different label.
 *
 * <p>The two inputs are the two commands: {@code validate} renders a run (named per-file reports),
 * {@code compile} a bare report (one schema, nothing to name).
 */
enum OutputFormat {
    TEXT, JSON, TSON;

    static OutputFormat parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "text" -> TEXT;
            case "json" -> JSON;
            case "tson" -> TSON;
            default -> throw new UsageException(
                    "unknown --output format '" + value + "' -- expected text, json, or tson");
        };
    }

    String render(ValidationReport report) {
        return switch (this) {
            case TEXT -> renderText(report);
            case JSON -> renderJson(report);
            case TSON -> renderTson(report);
        };
    }

    /**
     * Renders a {@link CliPolicy} on its own -- {@code tson policy}, which is this record with no document
     * in hand at all.
     *
     * <p>That is the surface the design turns on: a sender that knows the policy before it writes never
     * writes the name that would be refused, where one that learns it from a refusal has already spent a
     * round trip. {@link #TSON} and {@link #JSON} emit the same {@code policy} shape the envelopes carry,
     * so a consumer parses one thing either way.
     */
    String render(CliPolicy policy) {
        return switch (this) {
            case TEXT -> renderText(policy);
            case JSON -> renderJson(policy);
            case TSON -> renderTson(policy);
        };
    }

    /**
     * Renders a whole {@code validate} run.
     *
     * <p><b>{@link #JSON} and {@link #TSON} emit the envelope whatever the file count</b> -- a harness
     * parses one document and finds the filenames inside it. {@link #TEXT} keeps the per-file
     * {@code # <file>} header instead, printed only when there is more than one file to tell apart:
     * that label is for a person reading a terminal, and it is precisely its being outside the object
     * that made the machine formats unparseable.
     */
    String render(ValidationRun run) {
        return switch (this) {
            case TEXT -> renderText(run);
            case JSON -> renderJson(run);
            case TSON -> renderTson(run);
        };
    }

    private static String renderText(ValidationRun run) {
        StringBuilder text = new StringBuilder();
        for (CliDiagnostic error : run.errors()) {
            text.append(renderText(false, List.of(error))).append(System.lineSeparator());
        }
        for (FileReport file : run.files()) {
            if (run.files().size() > 1) {
                text.append("# ").append(file.file()).append(System.lineSeparator());
            }
            text.append(renderText(file.valid(), file.errors())).append(System.lineSeparator());
        }
        text.append(refusalNote(run.policy(), run.files().stream()
                .flatMap(file -> file.errors().stream()).toList()));
        return text.toString().stripTrailing();
    }

    private static String renderJson(ValidationRun run) {
        StringBuilder json = new StringBuilder();
        json.append("{\"valid\":").append(run.valid()).append(",\"policy\":");
        jsonPolicy(json, run.policy());
        json.append(",\"files\":[");
        for (int i = 0; i < run.files().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            FileReport file = run.files().get(i);
            json.append("{\"file\":").append(jsonString(file.file()))
                    .append(",\"valid\":").append(file.valid())
                    .append(",\"errors\":");
            jsonErrors(json, file.errors());
            json.append('}');
        }
        json.append("],\"errors\":");
        jsonErrors(json, run.errors());
        return json.append('}').toString();
    }

    private static String renderTson(ValidationRun run) {
        try {
            return new TsonObjectWriter().toTson(run);
        } catch (TsonWriteException e) {
            throw new IllegalStateException("failed to render this CLI's own diagnostics as TSON", e);
        }
    }

    private static String renderText(ValidationReport report) {
        return (renderText(report.valid(), report.errors()) + System.lineSeparator()
                + refusalNote(report.policy(), report.errors())).stripTrailing();
    }

    /**
     * One file's -- or one schema's -- diagnostics, for a person reading a terminal. Takes the verdict and
     * the list rather than an envelope, since {@link #renderText(ValidationRun)} renders each of a run's
     * files through it and a run has one policy, not one per file.
     */
    private static String renderText(boolean valid, List<CliDiagnostic> errors) {
        if (valid) {
            return "OK";
        }
        StringBuilder text = new StringBuilder();
        for (CliDiagnostic error : errors) {
            text.append('[').append(error.code()).append("] ");
            // The data location if there is one, else the schema's: a problem in a schema has no data end,
            // and one found while reading a document has no schema pointer. Whichever end answers, both its
            // halves print -- [TSON-DATA] §8.1 requires source position in *all* error reports, and a
            // pointer alone ("/address/nested_bogus") does not tell a human which line to open.
            String location = location(error.path(), error.dataPosition());
            if (location.isEmpty()) {
                location = location(error.schemaPointer(), error.schemaPosition());
            }
            if (!location.isEmpty()) {
                text.append(location).append(": ");
            }
            text.append(error.message()).append(System.lineSeparator());
        }
        return text.toString().stripTrailing();
    }

    /**
     * The policy that judged, printed only when this run actually refused something.
     *
     * <p>{@link #JSON} and {@link #TSON} carry {@code policy} unconditionally, because a machine consumer
     * wants one shape; a person does not want a configuration dump on every clean run. What they do want,
     * at exactly the moment a name is refused, is to know that the verdict came from this deployment's
     * settings and not from the document -- which is the one case where "why does it pass on my machine" has
     * an answer that is not in the file they are looking at.
     */
    private static String refusalNote(CliPolicy policy, List<CliDiagnostic> errors) {
        if (errors.stream().noneMatch(error -> isRefusal(error.code()))) {
            return "";
        }
        return "note: refused under " + summary(policy) + " -- this processor's own configuration, not a"
                + " property of your document. `tson policy` prints it in full.";
    }

    /** [TSON-DATA] §8.2's three name-hygiene rules, one code each -- the outcomes {@link CliPolicy} explains. */
    private static boolean isRefusal(Diagnostic.Code code) {
        return code == Diagnostic.Code.CONFUSABLE_NAMES || code == Diagnostic.Code.RESTRICTED_CHARACTER
                || code == Diagnostic.Code.RESTRICTED_SCRIPT;
    }

    /** A policy on one line: what differs between two deployments that disagree about one name. */
    private static String summary(CliPolicy policy) {
        return "identifier policy " + summary(policy.identifierPolicy())
                + ", token policy " + summary(policy.tokenPolicy())
                + ", Unicode " + policy.unicodeDataVersion();
    }

    private static String summary(CliPolicy.CliUnicodePolicy policy) {
        return policy.level() + (policy.perSegment() ? " per segment" : "")
                + (policy.permitting().isEmpty() ? ""
                        : " permitting " + policy.permitting().stream().map(scripts ->
                                String.join("+", scripts)).toList());
    }

    /**
     * {@code tson policy}'s own rendering: both surfaces and the data version, one line each. Deliberately
     * the same wording as the note a refusal prints, so the two read as one fact stated twice rather than
     * as two things to reconcile.
     */
    private static String renderText(CliPolicy policy) {
        return "identifier policy: " + summary(policy.identifierPolicy()) + System.lineSeparator()
                + "token policy:      " + summary(policy.tokenPolicy()) + System.lineSeparator()
                + "unicode data:      " + policy.unicodeDataVersion();
    }

    /**
     * One end of a {@link CliDiagnostic}'s location, as {@code pointer (line:column:byteOffset)} -- either
     * half may be missing, and an end with neither renders empty so the caller can fall through to the other.
     * A position with no pointer is worth printing on its own: a base-syntax error has no path into a
     * document it could not parse, but it does know where it gave up.
     *
     * <p>The root pointer renders the same as an absent one, and deliberately: {@code ": message"} is noise
     * to a person reading a terminal, who is looking at the whole document either way. The distinction is
     * real and is preserved everywhere a consumer reads it -- {@link #JSON} and {@link #TSON} both emit
     * {@code ""} and {@code null} apart -- it just isn't worth a character here.
     */
    private static String location(Optional<String> pointer, Optional<String> position) {
        String rendered = pointer.orElse("");
        if (position.isEmpty()) {
            return rendered;
        }
        return rendered.isEmpty() ? "(" + position.get() + ")" : rendered + " (" + position.get() + ")";
    }

    /** Every {@link CliDiagnostic} field, not just {@code code}/{@code message} -- the primary alignment target this shape maps to, Pydantic v2's own {@code ValidationError.errors()} ({@code type}/{@code loc}/{@code msg}/{@code input}/{@code ctx}), needs all of it. */
    private static String renderJson(ValidationReport report) {
        StringBuilder json = new StringBuilder();
        json.append("{\"valid\":").append(report.valid()).append(",\"policy\":");
        jsonPolicy(json, report.policy());
        json.append(",\"errors\":");
        jsonErrors(json, report.errors());
        return json.append('}').toString();
    }

    private static void jsonErrors(StringBuilder json, List<CliDiagnostic> errors) {
        json.append('[');
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            CliDiagnostic error = errors.get(i);
            json.append("{\"path\":").append(jsonStringOrNull(error.path()))
                    .append(",\"schemaPointer\":").append(jsonStringOrNull(error.schemaPointer()))
                    .append(",\"schemaId\":").append(jsonStringOrNull(error.schemaId()))
                    .append(",\"code\":").append(jsonString(error.code().name()))
                    .append(",\"message\":").append(jsonString(error.message()))
                    .append(",\"expected\":").append(jsonStringOrNull(error.expected()))
                    .append(",\"actual\":").append(jsonStringOrNull(error.actual()))
                    .append(",\"dataPosition\":").append(jsonStringOrNull(error.dataPosition()))
                    .append(",\"schemaPosition\":").append(jsonStringOrNull(error.schemaPosition()))
                    .append(",\"fetchReason\":")
                    .append(jsonStringOrNull(error.fetchReason().map(Enum::name)))
                    .append('}');
        }
        json.append(']');
    }

    private static String renderJson(CliPolicy policy) {
        StringBuilder json = new StringBuilder();
        jsonPolicy(json, policy);
        return json.toString();
    }

    private static void jsonPolicy(StringBuilder json, CliPolicy policy) {
        json.append("{\"identifierPolicy\":");
        jsonUnicodePolicy(json, policy.identifierPolicy());
        json.append(",\"tokenPolicy\":");
        jsonUnicodePolicy(json, policy.tokenPolicy());
        json.append(",\"unicodeDataVersion\":").append(jsonString(policy.unicodeDataVersion())).append('}');
    }

    private static void jsonUnicodePolicy(StringBuilder json, CliPolicy.CliUnicodePolicy policy) {
        json.append("{\"level\":").append(jsonString(policy.level().name()))
                .append(",\"perSegment\":").append(policy.perSegment())
                .append(",\"permitting\":[");
        for (int i = 0; i < policy.permitting().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('[');
            List<String> scripts = policy.permitting().get(i);
            for (int j = 0; j < scripts.size(); j++) {
                if (j > 0) {
                    json.append(',');
                }
                json.append(jsonString(scripts.get(j)));
            }
            json.append(']');
        }
        json.append("]}");
    }

    private static String jsonStringOrNull(Optional<String> value) {
        return value.map(OutputFormat::jsonString).orElse("null");
    }

    /**
     * Hand-rolled, deliberately minimal -- no external JSON dependency (this codebase's own hard
     * constraint), and this CLI's own diagnostics are simple, flat strings with no need for a real JSON
     * library's generality.
     */
    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    /**
     * Writes {@code report} via the plain, schemaless {@link TsonObjectWriter} (Class 1 -- there's no
     * schema-aware writer yet, tracked in {@code BACKLOG.md}'s "Write side").
     *
     * <p>The dogfooding claim -- that what this emits is genuinely valid against {@code diagnostics.tn},
     * not merely shaped like it -- is proven by {@code OutputFormatTest} reading every rendered report
     * back through that schema's own compiled {@code validation_report} reader. It is an invariant of
     * this method, so it is asserted once in a test rather than re-derived on every render.
     */
    private static String renderTson(ValidationReport report) {
        try {
            return new TsonObjectWriter().toTson(report);
        } catch (TsonWriteException e) {
            throw new IllegalStateException("failed to render this CLI's own diagnostics as TSON", e);
        }
    }

    /** {@code tson policy} as TSON -- the same {@code policy} record the envelopes carry, on its own. */
    private static String renderTson(CliPolicy policy) {
        try {
            return new TsonObjectWriter().toTson(policy);
        } catch (TsonWriteException e) {
            throw new IllegalStateException("failed to render this CLI's own policy as TSON", e);
        }
    }
}
