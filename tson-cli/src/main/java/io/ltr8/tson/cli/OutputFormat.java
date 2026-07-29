package io.ltr8.tson.cli;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.mapper.TsonMapperWriter;

import java.util.Locale;

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
            text.append('[').append(error.code()).append("] ").append(error.message())
                    .append(System.lineSeparator());
        }
        return text.toString().stripTrailing();
    }

    private static String renderJson(ValidationReport report) {
        StringBuilder json = new StringBuilder();
        json.append("{\"valid\":").append(report.valid()).append(",\"errors\":[");
        for (int i = 0; i < report.errors().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            CliDiagnostic error = report.errors().get(i);
            json.append("{\"code\":").append(jsonString(error.code()))
                    .append(",\"message\":").append(jsonString(error.message())).append('}');
        }
        json.append("]}");
        return json.toString();
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
     * Writes {@code report} via the plain, schemaless {@link TsonMapperWriter} (Class 1 -- there's
     * no schema-aware writer yet, tracked in {@code BACKLOG.md}'s "Write side"), then reads it back
     * through {@code diagnostics.tn1}'s own compiled {@code validation_report} reader -- proving the
     * emitted text is genuinely valid against a real TSON schema, not just structurally similar to
     * one written by hand.
     */
    private static String renderTson(ValidationReport report) {
        try {
            String text = new TsonMapperWriter().toTson(report);
            Object reread = DiagnosticsSchema.compiled().compiledSchema().get("validation_report")
                    .read(new TsonDataParser(text).parseDocument().root());
            if (!(reread instanceof ValidationReport)) {
                throw new IllegalStateException("diagnostics.tn1's own validation_report read back as "
                        + reread.getClass() + ", not ValidationReport");
            }
            return text;
        } catch (DataBindException e) {
            throw new IllegalStateException("failed to render this CLI's own diagnostics as TSON", e);
        }
    }
}
