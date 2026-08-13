package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.TsonWriteException;

import java.util.Locale;
import java.util.Optional;

/**
 * How a {@link ValidationReport} is printed -- {@link #TEXT} for a human reading a terminal, {@link
 * #JSON} for a script/agent (and interop with the JSON-Schema/pydantic tooling ecosystem), and
 * {@link #TSON} for a real, schema-validated TSON document: this CLI's own diagnostics dogfooding
 * the library, not just JSON with a different label.
 */
enum OutputFormat {
    TEXT, JSON, TSON;

    static OutputFormat parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "text" -> TEXT;
            case "json" -> JSON;
            case "tson" -> TSON;
            default -> throw new IllegalArgumentException(
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

    private static String renderText(ValidationReport report) {
        if (report.valid()) {
            return "OK";
        }
        StringBuilder text = new StringBuilder();
        for (CliDiagnostic error : report.errors()) {
            text.append('[').append(error.code()).append("] ");
            if (!error.path().isEmpty()) {
                text.append(error.path()).append(": ");
            }
            text.append(error.message()).append(System.lineSeparator());
        }
        return text.toString().stripTrailing();
    }

    /** Every {@link CliDiagnostic} field, not just {@code code}/{@code message} -- the primary alignment target this shape maps to, Pydantic v2's own {@code ValidationError.errors()} ({@code type}/{@code loc}/{@code msg}/{@code input}/{@code ctx}), needs all of it. */
    private static String renderJson(ValidationReport report) {
        StringBuilder json = new StringBuilder();
        json.append("{\"valid\":").append(report.valid()).append(",\"errors\":[");
        for (int i = 0; i < report.errors().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            CliDiagnostic error = report.errors().get(i);
            json.append("{\"path\":").append(jsonString(error.path()))
                    .append(",\"code\":").append(jsonString(error.code().name()))
                    .append(",\"message\":").append(jsonString(error.message()))
                    .append(",\"expected\":").append(jsonString(error.expected()))
                    .append(",\"actual\":").append(jsonString(error.actual()))
                    .append(",\"dataPosition\":").append(jsonStringOrNull(error.dataPosition()))
                    .append(",\"schemaPosition\":").append(jsonStringOrNull(error.schemaPosition()))
                    .append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private static String jsonStringOrNull(Optional<String> value) {
        return value.map(OutputFormat::jsonString).orElse("null");
    }

    /** Hand-rolled, deliberately minimal -- no external JSON dependency (this codebase's own hard constraint), and this CLI's own diagnostics are simple, flat strings with no need for a real JSON library's generality. */
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
}
