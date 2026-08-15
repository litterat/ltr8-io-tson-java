package io.ltr8.tson.cli;

import io.ltr8.annotation.Field;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * This CLI's own on-the-wire shape for {@link Diagnostic} -- same fields, but {@code
 * dataPosition}/{@code schemaPosition} are pre-rendered to {@code "line:column:byteOffset"} strings
 * rather than kept as {@link SourcePosition} objects. Deliberate: {@code diagnostics.tn}'s own {@code
 * data_position}/{@code schema_position} fields are plain {@code text}, and a raw bound string can't
 * be narrowed back into an arbitrary atom-bridged type the way an enum or a number can ({@code
 * RecordBindReader}'s own narrowing only knows about a handful of specific cases) -- keeping this as
 * a separate, string-only DTO sidesteps that gap entirely rather than risking it. {@code code} stays
 * the real {@link Diagnostic.Code} enum, since enum narrowing *is* a proven, already-used binding
 * path elsewhere in this codebase.
 */
public record CliDiagnostic(String path, @Field("schema_pointer") String schemaPointer,
                             @Field("schema_id") String schemaId,
                             Diagnostic.Code code, String message, String expected, String actual,
                             @Field("data_position") Optional<String> dataPosition,
                             @Field("schema_position") Optional<String> schemaPosition) {

    static CliDiagnostic from(Diagnostic diagnostic) {
        return new CliDiagnostic(diagnostic.path(), diagnostic.schemaPointer(), diagnostic.schemaId(),
                diagnostic.code(), diagnostic.message(), diagnostic.expected(),
                diagnostic.actual(), diagnostic.dataPosition().map(CliDiagnostic::render),
                diagnostic.schemaPosition().map(CliDiagnostic::render));
    }

    static CliDiagnostic minimal(Diagnostic.Code code, String message) {
        return new CliDiagnostic("", "", "", code, message, "", "", Optional.empty(), Optional.empty());
    }

    private static String render(SourcePosition position) {
        return position.line() + ":" + position.column() + ":" + position.byteOffset();
    }
}
