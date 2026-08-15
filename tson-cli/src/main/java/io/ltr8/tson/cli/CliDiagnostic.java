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
 *
 * <p><b>An absent field is absent, not empty.</b> {@link Diagnostic} spells "nothing to say here"
 * as {@code ""} for its string components and as an empty {@link Optional} for its positions, so the
 * rendered output used to show both {@code ""} and {@code null} for the same idea. Here everything
 * that can be absent is an {@code Optional} and renders as {@code null}.
 *
 * <p><b>{@code path} and {@code schemaPointer} are the exceptions, and stay plain strings</b>, because
 * for an RFC 6901 pointer {@code ""} is not absence -- it is the root. A document-level schema problem
 * genuinely carries the root pointer ({@code Tson.validateSchema} reports one for an {@code !!import}
 * that won't load), and a base-syntax failure genuinely locates itself at the root of the data. Folding
 * those into {@code null} would erase a real distinction, and the distinction can only be drawn
 * properly on {@link Diagnostic} itself, where the same {@code ""} means both things today.
 */
public record CliDiagnostic(String path, @Field("schema_pointer") String schemaPointer,
                             @Field("schema_id") Optional<String> schemaId,
                             Diagnostic.Code code, String message,
                             Optional<String> expected, Optional<String> actual,
                             @Field("data_position") Optional<String> dataPosition,
                             @Field("schema_position") Optional<String> schemaPosition) {

    static CliDiagnostic from(Diagnostic diagnostic) {
        return new CliDiagnostic(diagnostic.path(), diagnostic.schemaPointer(),
                absentIfEmpty(diagnostic.schemaId()),
                diagnostic.code(), diagnostic.message(),
                absentIfEmpty(diagnostic.expected()), absentIfEmpty(diagnostic.actual()),
                diagnostic.dataPosition().map(CliDiagnostic::render),
                diagnostic.schemaPosition().map(CliDiagnostic::render));
    }

    static CliDiagnostic minimal(Diagnostic.Code code, String message) {
        return new CliDiagnostic("", "", Optional.empty(), code, message, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** {@link Diagnostic}'s "nothing to say here" for a string component, as an absence. */
    private static Optional<String> absentIfEmpty(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** The position format every rendered diagnostic uses; stated in {@code diagnostics.tn} for consumers. */
    private static String render(SourcePosition position) {
        return position.line() + ":" + position.column() + ":" + position.byteOffset();
    }
}
